import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
  ViewChild,
} from '@angular/core';
import {
  ConfigService,
  MessageService,
  NamedObjectId,
  ParameterSubscription,
  ParameterValue,
  WebappSdkModule,
  YamcsService,
  utils,
} from '@yamcs/webapp-sdk';
import { Viewer } from '../Viewer';
import { loadDbwrAssets } from './dbwr-assets';

// Globals provided by the vendored dbwr client runtime, loaded as scripts from
// the yamcs-bob-plugin (/bob/static/...). `PVWS` is the PV-source contract dbwr
// instantiates; we replace it with a bridge to the Yamcs parameter WebSocket.
declare const DisplayBuilderWebRuntime: any;
declare global {
  interface Window {
    PVWS: any;
    dbwr: any;
    jQuery: any;
  }
}

/**
 * Renders a Phoebus `.bob` display.
 *
 * The dbwr server route (`/bob/render`) turns the `.bob` into an HTML fragment;
 * the dbwr client runtime (`dbwr.js` + `widgets/*.js`) animates it. This
 * component loads that runtime, injects the fragment, and feeds it live data by
 * implementing dbwr's `PVWS` interface on top of
 * `yamcsClient.createParameterSubscription`, translating each Yamcs
 * `ParameterValue` into the dbwr update message shape.
 *
 * NOTE: read-only display (M2). Write-back / commanding is Phase 3.
 */
@Component({
  standalone: true,
  template: `
    <div class="bob-viewer">
      <div id="content" #content></div>
      <!-- dbwr addresses these by id; kept present but hidden. -->
      <div id="info_panel" style="display: none">
        <span id="info"></span>
        <img id="status" alt="" />
      </div>
    </div>
  `,
  styles: [
    `
      .bob-viewer {
        position: relative;
        overflow: auto;
        width: 100%;
        height: 100%;
      }
    `,
  ],
  imports: [WebappSdkModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BobDisplayViewerComponent implements Viewer, OnDestroy {
  @ViewChild('content', { static: true })
  private content: ElementRef<HTMLDivElement>;

  private bucket: string;

  private runtime: any;
  private onConnect?: (connected: boolean) => void;
  private onMessage?: (message: any) => void;

  // One Yamcs subscription multiplexes all PVs. dbwr calls subscribe(pv) once
  // per unique name as it initializes widgets (synchronously), so we collect
  // names and (re)create the subscription on the next tick.
  private subscription?: ParameterSubscription;
  private pvNames = new Set<string>();
  private flushTimer?: ReturnType<typeof setTimeout>;
  private idMapping: { [numericId: number]: NamedObjectId } = {};
  private idInfo: { [numericId: number]: any } = {};

  constructor(
    private yamcs: YamcsService,
    private configService: ConfigService,
    private messageService: MessageService,
  ) {
    this.bucket = configService.getDisplayBucket();
  }

  async init(objectName: string): Promise<any> {
    const baseHref = this.yamcs.yamcsClient!.baseHref; // e.g. '/'
    const bobBase = `${baseHref}bob`;
    const fetchFn = (url: string) => this.yamcs.yamcsClient!.doFetch(url);

    try {
      // 1. Load the dbwr client runtime (once per app session).
      await loadDbwrAssets(bobBase, fetchFn);

      // 2. Install the PV bridge dbwr will instantiate as `new PVWS(...)`.
      this.installPvBridge();

      // 3. Fetch the server-rendered HTML fragment and inject it.
      const url =
        `${bobBase}/render?bucket=${encodeURIComponent(this.bucket)}` +
        `&object=${encodeURIComponent(objectName)}`;
      const response = await fetchFn(url);
      if (!response.ok) {
        throw new Error(`Render failed (${response.status})`);
      }
      this.content.nativeElement.innerHTML = await response.text();

      // 4. Boot the dbwr runtime against the injected DOM. The constructor
      // creates our PVWS bridge (capturing the callbacks); opening it triggers
      // widget initialization, which subscribes the PVs.
      this.runtime = new DisplayBuilderWebRuntime(null);
      window.dbwr = this.runtime;
      this.runtime.pvws.open();
    } catch (err: any) {
      this.messageService.showError(err);
    }
  }

  private installPvBridge() {
    const self = this;
    window.PVWS = class {
      constructor(
        _url: string,
        onConnect: (connected: boolean) => void,
        onMessage: (message: any) => void,
      ) {
        self.onConnect = onConnect;
        self.onMessage = onMessage;
      }
      open() {
        self.onConnect?.(true);
      }
      close() {
        self.onConnect?.(false);
      }
      subscribe(pvName: string) {
        self.addPv(pvName);
      }
      clear(pvName: string) {
        self.removePv(pvName);
      }
      write(pvName: string, value: any) {
        // Write-back / commanding is Phase 3.
        console.warn(`[bob] write to ${pvName} (${value}) not yet supported`);
      }
    };
  }

  private addPv(pvName: string) {
    if (!this.pvNames.has(pvName)) {
      this.pvNames.add(pvName);
      this.scheduleResubscribe();
    }
  }

  private removePv(pvName: string) {
    if (this.pvNames.delete(pvName)) {
      this.scheduleResubscribe();
    }
  }

  private scheduleResubscribe() {
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
    }
    this.flushTimer = setTimeout(() => this.resubscribe(), 0);
  }

  private resubscribe() {
    if (this.subscription) {
      this.subscription.cancel();
      this.subscription = undefined;
    }
    this.idMapping = {};
    this.idInfo = {};

    const ids: NamedObjectId[] = [...this.pvNames].map((name) => ({ name }));
    if (!ids.length) {
      return;
    }

    this.subscription = this.yamcs.yamcsClient!.createParameterSubscription(
      {
        instance: this.yamcs.instance!,
        processor: this.yamcs.processor!,
        id: ids,
        abortOnInvalid: false,
        sendFromCache: true,
        updateOnExpiration: true,
        action: 'REPLACE',
      },
      (data) => {
        if (data.mapping) {
          this.idMapping = data.mapping;
        }
        if (data.info) {
          this.idInfo = data.info;
        }
        for (const id of data.invalid || []) {
          this.deliver(id.name, undefined);
        }
        for (const pval of data.values || []) {
          const id = this.idMapping[pval.numericId];
          const info = this.idInfo[pval.numericId];
          if (id) {
            this.deliver(id.name, pval, info);
          }
        }
      },
    );
  }

  /** Translate a Yamcs ParameterValue into a dbwr update message. */
  private deliver(pvName: string, pval?: ParameterValue, info?: any) {
    if (!this.onMessage) {
      return;
    }
    const message: any = { type: 'update', pv: pvName };

    if (!pval) {
      message.severity = 'UNDEFINED';
      this.onMessage(message);
      return;
    }

    message.severity = this.toSeverity(pval);
    if (pval.engValue) {
      message.value = utils.convertValue(pval.engValue);
      if (pval.engValue.type === 'ENUMERATED') {
        message.value = Number(pval.engValue.sint64Value);
        message.text = pval.engValue.stringValue;
      }
    }
    if (info?.enumValues) {
      message.labels = info.enumValues.map((x: any) => x.label);
    }
    if (info?.units) {
      message.units = info.units;
    }
    // Yamcs telemetry parameters are generally not writable (Phase 3).
    message.readonly = true;

    this.onMessage(message);
  }

  private toSeverity(pval: ParameterValue): string {
    if (
      pval.acquisitionStatus === 'EXPIRED' ||
      pval.acquisitionStatus === 'NOT_RECEIVED' ||
      pval.acquisitionStatus === 'INVALID'
    ) {
      return 'INVALID';
    }
    switch (pval.monitoringResult) {
      case 'WATCH':
      case 'WARNING':
      case 'DISTRESS':
        return 'MINOR';
      case 'CRITICAL':
      case 'SEVERE':
        return 'MAJOR';
      default:
        return 'NONE';
    }
  }

  hasPendingChanges(): boolean {
    return false;
  }

  ngOnDestroy(): void {
    if (this.flushTimer) {
      clearTimeout(this.flushTimer);
    }
    this.subscription?.cancel();
    if (window.dbwr === this.runtime) {
      window.dbwr = undefined;
    }
  }
}
