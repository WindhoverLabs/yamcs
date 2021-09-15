package org.yamcs.simulator;

import java.nio.ByteBuffer;

public class CFSFlightData extends FlightData {

    // Land_C[3][10]
    private final static int DIM_SIZE = 3;
    public double[] flatMatrix = new double[DIM_SIZE];
    public double[][] matrix2D = new double[DIM_SIZE][DIM_SIZE];
    public double[][][] matrix3D = new double[DIM_SIZE][DIM_SIZE][DIM_SIZE];

    public CFSFlightData() {
        super();
    }

    public void fillPacket(ByteBuffer buffer) {
        super.fillPacket(buffer);

        //TODO:Put these into functions
        
        for (int i = 0; i < flatMatrix.length; i++) {
            buffer.putDouble(flatMatrix[i]);
        }

        for (int i = 0; i < matrix2D.length; i++) {
            for (int j = 0; j < matrix2D[i].length; j++) {
                buffer.putDouble(matrix2D[i][j]);
            }
        }
        
        for (int i = 0; i < matrix3D.length; i++) {
            for (int j = 0; j < matrix3D[i].length; j++) {
                for (int k = 0; k < matrix3D[i][j].length; k++){
                    buffer.putDouble(matrix3D[i][j][k]);
                }
            }
        }
    }

    public static int size() {
        return FlightData.size() +
                (DIM_SIZE * Double.BYTES) + //flat
                ((DIM_SIZE * DIM_SIZE) * (Double.BYTES)) + // two dimensions
                ((DIM_SIZE * DIM_SIZE * DIM_SIZE) * (Double.BYTES)); //three dimensions
    }

}
