/**
 * Loads the vendored dbwr client runtime (jQuery, flot, dbwr.js, widget JS/CSS)
 * served by the yamcs-bob-plugin. The ordered asset list comes from the
 * server's `/bob/bootstrap` manifest so the client stays version-agnostic.
 *
 * Loaded once per app session; subsequent displays reuse the same runtime.
 */

let loaded: Promise<void> | null = null;

interface Manifest {
  css: string[];
  js: string[];
}

export function loadDbwrAssets(
  bobBase: string,
  fetchFn: (url: string) => Promise<Response>,
): Promise<void> {
  if (!loaded) {
    loaded = doLoad(bobBase, fetchFn).catch((err) => {
      // Allow a later retry if loading failed.
      loaded = null;
      throw err;
    });
  }
  return loaded;
}

async function doLoad(
  bobBase: string,
  fetchFn: (url: string) => Promise<Response>,
): Promise<void> {
  const response = await fetchFn(`${bobBase}/bootstrap`);
  if (!response.ok) {
    throw new Error(`Could not load bob asset manifest (${response.status})`);
  }
  const manifest: Manifest = await response.json();

  for (const href of manifest.css) {
    loadCss(`${bobBase}/${href}`);
  }
  // Scripts must load in order (jQuery -> dbwr.js -> widgets).
  for (const src of manifest.js) {
    await loadScript(`${bobBase}/${src}`);
  }
}

function loadCss(href: string) {
  if (document.querySelector(`link[data-bob="${href}"]`)) {
    return;
  }
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = href;
  link.dataset['bob'] = href;
  document.head.appendChild(link);
}

function loadScript(src: string): Promise<void> {
  const existing = document.querySelector(`script[data-bob="${src}"]`);
  if (existing) {
    return Promise.resolve();
  }
  return new Promise<void>((resolve, reject) => {
    const script = document.createElement('script');
    script.src = src;
    script.async = false;
    script.dataset['bob'] = src;
    script.onload = () => resolve();
    script.onerror = () => reject(new Error(`Failed to load ${src}`));
    document.head.appendChild(script);
  });
}
