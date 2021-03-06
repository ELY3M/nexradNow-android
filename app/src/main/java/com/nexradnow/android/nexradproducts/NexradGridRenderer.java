package com.nexradnow.android.nexradproducts;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Log;

import com.nexradnow.android.exception.NexradNowException;
import com.nexradnow.android.model.LatLongCoordinates;
import com.nexradnow.android.model.LatLongRect;
import com.nexradnow.android.model.LatLongScaler;
import com.nexradnow.android.model.NexradProduct;
import com.nexradnow.android.model.NexradStation;
import ucar.ma2.ArrayFloat;
import ucar.nc2.Dimension;
import ucar.nc2.NetcdfFile;
import ucar.nc2.Variable;

import java.util.Collection;
import java.util.List;

/**
 * Created by hobsonm on 10/15/15.
 */
public abstract class NexradGridRenderer implements NexradRenderer {
    protected static final String TAG = "NexradGridRenderer";
    @Override
    public LatLongRect findExtents(NexradProduct product) throws NexradNowException {
        LatLongRect result = null;
        try {
            NetcdfFile netcdfFile = NetcdfFile.openInMemory("sn.last", product.getBinaryData());
            float latMin = netcdfFile.findGlobalAttribute("geospatial_lat_min").getNumericValue().floatValue();
            float latMax = netcdfFile.findGlobalAttribute("geospatial_lat_max").getNumericValue().floatValue();
            float lonMin = netcdfFile.findGlobalAttribute("geospatial_lon_min").getNumericValue().floatValue();
            float lonMax = netcdfFile.findGlobalAttribute("geospatial_lon_max").getNumericValue().floatValue();
            if (lonMin > lonMax) {
                float temp = lonMax;
                lonMax = lonMin;
                lonMin = temp;
            }
            if (latMin > latMax) {
                float temp = latMax;
                latMax = latMin;
                latMin = temp;
            }
            result = new LatLongRect(lonMin,latMax,lonMax,latMin);
            netcdfFile.close();
        } catch (Exception ex) {
            String message = "error computing bounds of radar data for station "+product.getStation().getIdentifier();
            throw new NexradNowException(message);
        }
        return result;
    }

    @Override
    public abstract String getProductCode();

    @Override
    public abstract String getProductDescription();

    @Override
    public void renderToCanvas(Canvas canvas, NexradProduct product, Paint paint, LatLongScaler scaler, int minFeatureSize)
            throws NexradNowException {
        renderToCanvas(canvas, product, paint, scaler, minFeatureSize, 0.0, null, null);
    }

    @Override
    public void renderToCanvas(Canvas canvas, NexradProduct product, Paint paint, LatLongScaler scaler, int minFeatureSize,
                               double safeDistance, NexradStation dataOwner, Collection<NexradStation> otherStations)
            throws NexradNowException {
        Paint productPaint = paint;
        byte[] rawData = product.getBinaryData();

        try {
            NetcdfFile netcdfFile = NetcdfFile.openInMemory("sn.last", rawData);
            float latMin = netcdfFile.findGlobalAttribute("geospatial_lat_min").getNumericValue().floatValue();
            float latMax = netcdfFile.findGlobalAttribute("geospatial_lat_max").getNumericValue().floatValue();
            float lonMin = netcdfFile.findGlobalAttribute("geospatial_lon_min").getNumericValue().floatValue();
            float lonMax = netcdfFile.findGlobalAttribute("geospatial_lon_max").getNumericValue().floatValue();
            float latSpan = latMax - latMin;
            if (latSpan < 0) {
                latSpan = -latSpan;
            }
            float lonSpan = lonMax - lonMin;
            if (lonSpan < 0) {
                lonSpan = -lonSpan;
            }
            float latOrigin = Math.min(latMin, latMax);
            float lonOrigin = Math.min(lonMin, lonMax);
            float valMax = netcdfFile.findGlobalAttribute("data_max").getNumericValue().floatValue();
            Variable valueVariable = netcdfFile.findVariable("BaseReflectivityComp");
            // TODO search file for a square array of floating point values, regardless of name
            ArrayFloat.D2 floatArray = (ArrayFloat.D2)valueVariable.read();
            List<Dimension> dimensions = valueVariable.getDimensions();
            if ((dimensions.size() != 2)||(dimensions.get(0).getLength()!=dimensions.get(1).getLength())) {
                StringBuilder message = new StringBuilder();
                message.append("[");
                for (int index=0; index < dimensions.size(); index++) {
                    message.append(Integer.toString(dimensions.get(index).getLength()));
                    if (index != dimensions.size()-1) {
                        message.append(",");
                    }
                }
                message.append("]");
                throw new NexradNowException("unexpected data dimensionality: "+message.toString());
            }
            int numCells = dimensions.get(0).getLength();
            for (int y=0; y< numCells; y++) {
                for (int x=0; x<numCells; x++) {
                    float cellValue = floatArray.get(y, x);
                    if ((cellValue <= 0)||(Float.isNaN(cellValue))) {
                        continue;
                    }
                    // Translate to location and fill rectangle with value
                    int color = getColorFromTable(cellValue);
                    ///TODO DEBUG!!!
                    /*
                    if (cellValue > 53) {
                        Log.i(TAG, "cellValue: " + cellValue);
                        //Log.i(TAG, "color: " + color);
                        //Log.i(TAG, "colortable: "+colorTable[(int)cellValue]);
                    }
                    */
                    float ptLatStart = (float)((float)numCells-y)/(float)numCells*latSpan + latOrigin;
                    float ptLonStart = (float)x/(float)numCells*lonSpan + lonOrigin;
                    LatLongCoordinates origin = new LatLongCoordinates(ptLatStart, ptLonStart);
                    // check distance against min distance
                    if (dataOwner != null) {
                        if (origin.distanceTo(dataOwner.getCoords()) > safeDistance) {
                            // Need to check against all other stations
                            NexradStation closestStation = null;
                            double closestDistance = 0.0;
                            for (NexradStation station:otherStations) {
                                if ((closestStation == null)||(station.getCoords().distanceTo(origin) < closestDistance)) {
                                    closestStation = station;
                                    closestDistance = station.getCoords().distanceTo(origin);
                                }
                            }
                            if (!closestStation.getIdentifier().equals(dataOwner.getIdentifier())) {
                                continue;
                            }
                        }
                    }
                    LatLongCoordinates extent = new LatLongCoordinates(ptLatStart+latSpan/(float)numCells,ptLonStart+lonSpan/(float)numCells);
                    if (productPaint == null) {
                        productPaint = new Paint();
                    }
                    Paint cellBrush = productPaint;
                    cellBrush.setColor(color);
                    Rect paintRect = new Rect();
                    PointF ptOrigin = scaler.scaleLatLong(origin);
                    PointF ptExtent = scaler.scaleLatLong(extent);
                    paintRect.set((int)ptOrigin.x,(int)ptExtent.y,(int)ptExtent.x,(int)ptOrigin.y);
                    canvas.drawRect(paintRect,cellBrush);
                }
            }
            netcdfFile.close();
        } catch (Exception e) {
            String message = "cannot read Nexrad product data: "+e.toString();
            throw new NexradNowException(message);
        }


    }

    /**
     * Generate a color from green->red, depending on value of input. For 1.0, create pure red,
     * for 0.0, create a green.
     *
     * @param power value ranging from 1.0 to 0.0
     * @return color value ranging from a light green to a pure red
     */
    protected int getColor(float power)
    {
        // TODO: create different strategies for generating colors so we can (hopefully) plot other products someday
        float H = (1.0f-power) * 120f; // Hue (note 0.4 = Green, see huge chart below)
        float S = 0.9f; // Saturation
        float B = 0.9f; // Brightness
        float[] hsv = new float[3];
        hsv[0] = H;
        hsv[1] = S;
        hsv[2] = B;
        return Color.HSVToColor(hsv);
    }

    /*
//'from pykl3
Color,-33,30,30,30,71,71,71
Color,0,71,71,71,18,20,23
Color,3,18,20,23,22,25,30
Color,4,22,25,30,25,30,38
Color,5,25,30,38,27,33,44
Color,6,27,33,44,28,36,51
Color,7,28,36,51,28,37,58
Color,8,28,37,58,27,39,65
Color,9,27,39,65,31,47,74
Color,10,31,47,74,37,56,84
Color,11,37,56,84,43,67,95
Color,12,43,67,95,49,78,106
Color,13,49,78,106,56,91,118
Color,14,56,91,118,64,104,130
Color,15,64,104,130,71,118,142
Color,16,71,118,142,79,133,155
Color,17,79,133,155,88,149,169
Color,18,88,149,169,92,157,174
Color,19,92,157,174,60,173,110
Color,20,60,173,110,42,175,72
Color,21,42,175,72,37,163,62
Color,22,37,163,62,31,150,51
Color,23,31,150,51,26,137,40
Color,24,26,137,40,21,125,30
Color,25,21,125,30,16,112,19
Color,26,16,112,19,11,100,9
Color,27,11,100,9,59,130,7
Color,28,59,130,7,107,161,5
Color,29,107,161,5,155,191,3
Color,30,155,191,3,203,222,1
Color,31,203,222,1,252,253,0
Color,32,252,253,0,244,243,0
Color,33,244,243,0,237,233,0
Color,34,237,233,0,230,223,0
Color,35,230,223,0,222,213,0
Color,36,222,213,0,215,203,0
Color,37,215,203,0,208,193,0
Color,38,208,193,0,200,183,0
Color,39,200,183,0,250,148,0
Color,40,250,148,0,179,88,12
Color,50,249,35,11,130,40,32
Color,60,202,153,180,193,28,117
Color,70,154,36,224,62,15,140
Color,80,132,253,255,68,118,143
Color,90,161,101,73,118,15,6
Color,95,118,15,6,118,15,6
/*

            Color.rgb(30,30,30), // -33 db
            Color.rgb(71,71,71), // 0 db
            Color.rgb(18,20,23), // 3 db
            Color.rgb(22,25,30), // 4 db
            Color.rgb(25,30,38), // 5 db
            Color.rgb(27,33,44), // 6 db
            Color.rgb(28,36,51), // 7 db
            Color.rgb(28,37,58), // 8 db
            Color.rgb(27,39,65), // 9 db
            Color.rgb(31,47,74), // 10 db
            Color.rgb(37,56,84), // 11 db
            Color.rgb(43,67,95), // 12 db
            Color.rgb(49,78,106), // 13 db
            Color.rgb(56,91,118), // 14 db
            Color.rgb(64,104,130), // 15 db
            Color.rgb(71,118,142), // 16 db
            Color.rgb(79,133,155), // 17 db
            Color.rgb(88,149,169), // 18 db
            Color.rgb(92,157,174), // 19 db
            Color.rgb(60,173,110), // 20 db
            Color.rgb(42,175,72), // 21 db
            Color.rgb(37,163,62), // 22 db
            Color.rgb(31,150,51), // 23 db
            Color.rgb(26,137,40), // 24 db
            Color.rgb(21,125,30), // 25 db
            Color.rgb(16,112,19), // 26 db
            Color.rgb(11,100,9), // 27 db
            Color.rgb(59,130,7), // 28 db
            Color.rgb(107,161,5), // 29 db
            Color.rgb(155,191,3), // 30 db
            Color.rgb(203,222,1), // 31 db
            Color.rgb(252,253,0), // 32 db
            Color.rgb(244,243,0), // 33 db
            Color.rgb(237,233,0), // 34 db
            Color.rgb(230,223,0), // 35 db
            Color.rgb(222,213,0), // 36 db
            Color.rgb(215,203,0), // 37 db
            Color.rgb(208,193,0), // 38 db
            Color.rgb(200,183,0), // 39 db
            Color.rgb(250,148,0), // 40 db
            Color.rgb(249,35,11), // 50 db
            Color.rgb(202,153,180), // 60 db
            Color.rgb(154,36,224), // 70 db
            Color.rgb(132,253,255), // 80 db
            Color.rgb(161,101,73), // 90 db
            Color.rgb(118,15,6), // 95 db
*/


    /*
    static int[] colorTable = {
            Color.rgb(30,30,30), // -33 db
            Color.rgb(30,30,30), // -10 db
            Color.rgb(30,30,30), // -9 db
            Color.rgb(30,30,30), // -8 db
            Color.rgb(30,30,30), // -7 db
            Color.rgb(30,30,30), // -6 db
            Color.rgb(30,30,30), // -5 db
            Color.rgb(30,30,30), // -4 db
            Color.rgb(30,30,30), // -3 db
            Color.rgb(30,30,30), // -2 db
            Color.rgb(30,30,30), // -1 db
            Color.rgb(71,71,71), // 0 db
            Color.rgb(71,71,71), // 1 db
            Color.rgb(71,71,71), // 2 db
            Color.rgb(18,20,23), // 3 db
            Color.rgb(22,25,30), // 4 db
            Color.rgb(25,30,38), // 5 db
            Color.rgb(27,33,44), // 6 db
            Color.rgb(28,36,51), // 7 db
            Color.rgb(28,37,58), // 8 db
            Color.rgb(27,39,65), // 9 db
            Color.rgb(31,47,74), // 10 db
            Color.rgb(37,56,84), // 11 db
            Color.rgb(43,67,95), // 12 db
            Color.rgb(49,78,106), // 13 db
            Color.rgb(56,91,118), // 14 db
            Color.rgb(64,104,130), // 15 db
            Color.rgb(71,118,142), // 16 db
            Color.rgb(79,133,155), // 17 db
            Color.rgb(88,149,169), // 18 db
            Color.rgb(92,157,174), // 19 db
            Color.rgb(60,173,110), // 20 db
            Color.rgb(42,175,72), // 21 db
            Color.rgb(37,163,62), // 22 db
            Color.rgb(31,150,51), // 23 db
            Color.rgb(26,137,40), // 24 db
            Color.rgb(21,125,30), // 25 db
            Color.rgb(16,112,19), // 26 db
            Color.rgb(11,100,9), // 27 db
            Color.rgb(59,130,7), // 28 db
            Color.rgb(107,161,5), // 29 db
            Color.rgb(155,191,3), // 30 db
            Color.rgb(203,222,1), // 31 db
            Color.rgb(252,253,0), // 32 db
            Color.rgb(244,243,0), // 33 db
            Color.rgb(237,233,0), // 34 db
            Color.rgb(230,223,0), // 35 db
            Color.rgb(222,213,0), // 36 db
            Color.rgb(215,203,0), // 37 db
            Color.rgb(208,193,0), // 38 db
            Color.rgb(200,183,0), // 39 db
            Color.rgb(250,148,0), // 40 db
            Color.rgb(250,148,0), // 41 db
            Color.rgb(250,148,0), // 42 db
            Color.rgb(250,148,0), // 43 db
            Color.rgb(250,148,0), // 44 db
            Color.rgb(250,148,0), // 45 db
            Color.rgb(250,148,0), // 46 db
            Color.rgb(250,148,0), // 47 db
            Color.rgb(250,148,0), // 48 db
            Color.rgb(250,148,0), // 49 db
            Color.rgb(249,35,11), // 50 db
            Color.rgb(249,35,11), // 51 db
            Color.rgb(249,35,11), // 52 db
            Color.rgb(249,35,11), // 53 db
            Color.rgb(249,35,11), // 54 db
            Color.rgb(249,35,11), // 55 db
            Color.rgb(249,35,11), // 56 db
            Color.rgb(249,35,11), // 57 db
            Color.rgb(249,35,11), // 58 db
            Color.rgb(249,35,11), // 59 db
            Color.rgb(202,153,180), // 60 db
            Color.rgb(202,153,180), // 61 db
            Color.rgb(202,153,180), // 62 db
            Color.rgb(202,153,180), // 63 db
            Color.rgb(202,153,180), // 64 db
            Color.rgb(202,153,180), // 65 db
            Color.rgb(202,153,180), // 66 db
            Color.rgb(202,153,180), // 67 db
            Color.rgb(202,153,180), // 68 db
            Color.rgb(202,153,180), // 69 db
            Color.rgb(154,36,224), // 70 db
            Color.rgb(154,36,224), // 71 db
            Color.rgb(154,36,224), // 72 db
            Color.rgb(154,36,224), // 73 db
            Color.rgb(154,36,224), // 74 db
            Color.rgb(154,36,224), // 75 db
            Color.rgb(154,36,224), // 76 db
            Color.rgb(154,36,224), // 77 db
            Color.rgb(154,36,224), // 78 db
            Color.rgb(154,36,224), // 79 db
            Color.rgb(132,253,255), // 80 db
            Color.rgb(132,253,255), // 81 db
            Color.rgb(132,253,255), // 82 db
            Color.rgb(132,253,255), // 83 db
            Color.rgb(132,253,255), // 84 db
            Color.rgb(132,253,255), // 85 db
            Color.rgb(132,253,255), // 86 db
            Color.rgb(132,253,255), // 87 db
            Color.rgb(132,253,255), // 88 db
            Color.rgb(132,253,255), // 89 db
            Color.rgb(161,101,73), // 90 db
            Color.rgb(161,101,73), // 91 db
            Color.rgb(161,101,73), // 92 db
            Color.rgb(161,101,73), // 93 db
            Color.rgb(161,101,73), // 94 db
            Color.rgb(118,15,6), // 95 db
            Color.rgb(118,15,6), // 96 db
            Color.rgb(118,15,6), // 97 db
            Color.rgb(118,15,6), // 98 db
            Color.rgb(118,15,6), // 99 db
            Color.rgb(118,15,6), // 100 db


    };




/*
    static int[] colorTable = {
            Color.rgb(93,225,117), // 5 db
            Color.rgb(80,197,65), // 10 db
            Color.rgb(65,164,49), // 15 db
            Color.rgb(53,136,40), // 20 db
            Color.rgb(41,109,37), // 25 db
            Color.rgb(34,94,31), // 30 db
            Color.rgb(249,239,0), // 35 db
            Color.rgb(238,188,0), // 40 db
            Color.rgb(240,144,0), // 45 db
            Color.rgb(228,108,0), // 50 db
            Color.rgb(214,42,0), // 55 db
            Color.rgb(164,29,0), // 60 db
            Color.rgb(217,35,143), // 65 db
            Color.rgb(159,0,211), // 70 db
            Color.rgb(106,2,118) // 75 db and up
    };
*/

/*
    static int[] colorTable = {

            Color.rgb(30, 30, 30), // -33 db
            Color.rgb(71, 71, 71), // 0 db
            Color.rgb(18, 20, 23), // 3 db
            Color.rgb(22, 25, 30), // 4 db
            Color.rgb(25, 30, 38), // 5 db
            Color.rgb(27, 33, 44), // 6 db
            Color.rgb(28, 36, 51), // 7 db
            Color.rgb(28, 37, 58), // 8 db
            Color.rgb(27, 39, 65), // 9 db
            Color.rgb(31, 47, 74), // 10 db
            Color.rgb(37, 56, 84), // 11 db
            Color.rgb(43, 67, 95), // 12 db
            Color.rgb(49, 78, 106), // 13 db
            Color.rgb(56, 91, 118), // 14 db
            Color.rgb(64, 104, 130), // 15 db
            Color.rgb(71, 118, 142), // 16 db
            Color.rgb(79, 133, 155), // 17 db
            Color.rgb(88, 149, 169), // 18 db
            Color.rgb(92, 157, 174), // 19 db
            Color.rgb(60, 173, 110), // 20 db
            Color.rgb(42, 175, 72), // 21 db
            Color.rgb(37, 163, 62), // 22 db
            Color.rgb(31, 150, 51), // 23 db
            Color.rgb(26, 137, 40), // 24 db
            Color.rgb(21, 125, 30), // 25 db
            Color.rgb(16, 112, 19), // 26 db
            Color.rgb(11, 100, 9), // 27 db
            Color.rgb(59, 130, 7), // 28 db
            Color.rgb(107, 161, 5), // 29 db
            Color.rgb(155, 191, 3), // 30 db
            Color.rgb(203, 222, 1), // 31 db
            Color.rgb(252, 253, 0), // 32 db
            Color.rgb(244, 243, 0), // 33 db
            Color.rgb(237, 233, 0), // 34 db
            Color.rgb(230, 223, 0), // 35 db
            Color.rgb(222, 213, 0), // 36 db
            Color.rgb(215, 203, 0), // 37 db
            Color.rgb(208, 193, 0), // 38 db
            Color.rgb(200, 183, 0), // 39 db
            Color.rgb(250, 148, 0), // 40 db
            Color.rgb(249, 35, 11), // 50 db
            Color.rgb(202, 153, 180), // 60 db
            Color.rgb(154, 36, 224), // 70 db
            Color.rgb(132, 253, 255), // 80 db
            Color.rgb(161, 101, 73), // 90 db
            Color.rgb(118, 15, 6), // 95 db

    };

*/

//TODO change to use pykl3 or grlevel3 pallete stored in \NexradNow\ dir
//TODO try to use two colors like in pykl3 and grlevel3
/*
    static int[] colorTable = {
            Color.rgb(30,30,30), // -33 db
            Color.rgb(71,71,71), // 0 db
            Color.rgb(71,71,71), // 1 db
            Color.rgb(71,71,71), // 2 db
            Color.rgb(18,20,23), // 3 db
            Color.rgb(22,25,30), // 4 db
            Color.rgb(25,30,38), // 5 db
            Color.rgb(27,33,44), // 6 db
            Color.rgb(28,36,51), // 7 db
            Color.rgb(28,37,58), // 8 db
            Color.rgb(27,39,65), // 9 db
            Color.rgb(31,47,74), // 10 db
            Color.rgb(37,56,84), // 11 db
            Color.rgb(43,67,95), // 12 db
            Color.rgb(49,78,106), // 13 db
            Color.rgb(56,91,118), // 14 db
            Color.rgb(64,104,130), // 15 db
            Color.rgb(71,118,142), // 16 db
            Color.rgb(79,133,155), // 17 db
            Color.rgb(88,149,169), // 18 db
            Color.rgb(92,157,174), // 19 db
            Color.rgb(60,173,110), // 20 db
            Color.rgb(42,175,72), // 21 db
            Color.rgb(37,163,62), // 22 db
            Color.rgb(31,150,51), // 23 db
            Color.rgb(26,137,40), // 24 db
            Color.rgb(21,125,30), // 25 db
            Color.rgb(16,112,19), // 26 db
            Color.rgb(11,100,9), // 27 db
            Color.rgb(59,130,7), // 28 db
            Color.rgb(107,161,5), // 29 db
            Color.rgb(155,191,3), // 30 db
            Color.rgb(203,222,1), // 31 db
            Color.rgb(252,253,0), // 32 db
            Color.rgb(244,243,0), // 33 db
            Color.rgb(237,233,0), // 34 db
            Color.rgb(230,223,0), // 35 db
            Color.rgb(222,213,0), // 36 db
            Color.rgb(215,203,0), // 37 db
            Color.rgb(208,193,0), // 38 db
            Color.rgb(200,183,0), // 39 db
            Color.rgb(250,148,0), // 40 db
            Color.rgb(250,148,0), // 41 db
            Color.rgb(250,148,0), // 42 db
            Color.rgb(250,148,0), // 43 db
            Color.rgb(250,148,0), // 44 db
            Color.rgb(250,148,0), // 45 db
            Color.rgb(250,148,0), // 46 db
            Color.rgb(250,148,0), // 47 db
            Color.rgb(250,148,0), // 48 db
            Color.rgb(250,148,0), // 49 db
            Color.rgb(249,35,11), // 50 db
            Color.rgb(249,35,11), // 51 db
            Color.rgb(249,35,11), // 52 db
            Color.rgb(249,35,11), // 53 db
            Color.rgb(249,35,11), // 54 db
            Color.rgb(249,35,11), // 55 db
            Color.rgb(249,35,11), // 56 db
            Color.rgb(249,35,11), // 57 db
            Color.rgb(249,35,11), // 58 db
            Color.rgb(249,35,11), // 59 db
            Color.rgb(202,153,180), // 60 db
            Color.rgb(202,153,180), // 61 db
            Color.rgb(202,153,180), // 62 db
            Color.rgb(202,153,180), // 63 db
            Color.rgb(202,153,180), // 64 db
            Color.rgb(202,153,180), // 65 db
            Color.rgb(202,153,180), // 66 db
            Color.rgb(202,153,180), // 67 db
            Color.rgb(202,153,180), // 68 db
            Color.rgb(202,153,180), // 69 db
            Color.rgb(154,36,224), // 70 db
            Color.rgb(154,36,224), // 71 db
            Color.rgb(154,36,224), // 72 db
            Color.rgb(154,36,224), // 73 db
            Color.rgb(154,36,224), // 74 db
            Color.rgb(154,36,224), // 75 db
            Color.rgb(154,36,224), // 76 db
            Color.rgb(154,36,224), // 77 db
            Color.rgb(154,36,224), // 78 db
            Color.rgb(154,36,224), // 79 db
            Color.rgb(132,253,255), // 80 db
            Color.rgb(132,253,255), // 81 db
            Color.rgb(132,253,255), // 82 db
            Color.rgb(132,253,255), // 83 db
            Color.rgb(132,253,255), // 84 db
            Color.rgb(132,253,255), // 85 db
            Color.rgb(132,253,255), // 86 db
            Color.rgb(132,253,255), // 87 db
            Color.rgb(132,253,255), // 88 db
            Color.rgb(132,253,255), // 89 db
            Color.rgb(161,101,73), // 90 db
            Color.rgb(161,101,73), // 91 db
            Color.rgb(161,101,73), // 92 db
            Color.rgb(161,101,73), // 93 db
            Color.rgb(161,101,73), // 94 db
            Color.rgb(118,15,6), // 95 db
            Color.rgb(118,15,6), // 96 db
            Color.rgb(118,15,6), // 97 db
            Color.rgb(118,15,6), // 98 db
            Color.rgb(118,15,6), // 99 db


    };
*/

    static int[] colorTable = {

            Color.rgb(30, 30, 30), // -33 db
            Color.rgb(71, 71, 71), // 0 db
            Color.rgb(18, 20, 23), // 3 db
            Color.rgb(22, 25, 30), // 4 db
            Color.rgb(25, 30, 38), // 5 db
            Color.rgb(27, 33, 44), // 6 db
            Color.rgb(28, 36, 51), // 7 db
            Color.rgb(28, 37, 58), // 8 db
            Color.rgb(27, 39, 65), // 9 db
            Color.rgb(31, 47, 74), // 10 db
            Color.rgb(37, 56, 84), // 11 db
            Color.rgb(43, 67, 95), // 12 db
            Color.rgb(49, 78, 106), // 13 db
            Color.rgb(56, 91, 118), // 14 db
            Color.rgb(64, 104, 130), // 15 db
            Color.rgb(71, 118, 142), // 16 db
            Color.rgb(79, 133, 155), // 17 db
            Color.rgb(88, 149, 169), // 18 db
            Color.rgb(92, 157, 174), // 19 db
            Color.rgb(60, 173, 110), // 20 db
            Color.rgb(42, 175, 72), // 21 db
            Color.rgb(37, 163, 62), // 22 db
            Color.rgb(31, 150, 51), // 23 db
            Color.rgb(26, 137, 40), // 24 db
            Color.rgb(21, 125, 30), // 25 db
            Color.rgb(16, 112, 19), // 26 db
            Color.rgb(11, 100, 9), // 27 db
            Color.rgb(59, 130, 7), // 28 db
            Color.rgb(107, 161, 5), // 29 db
            Color.rgb(155, 191, 3), // 30 db
            Color.rgb(203, 222, 1), // 31 db
            Color.rgb(252, 253, 0), // 32 db
            Color.rgb(244, 243, 0), // 33 db
            Color.rgb(237, 233, 0), // 34 db
            Color.rgb(230, 223, 0), // 35 db
            Color.rgb(222, 213, 0), // 36 db
            Color.rgb(215, 203, 0), // 37 db
            Color.rgb(208, 193, 0), // 38 db
            Color.rgb(200, 183, 0), // 39 db
            Color.rgb(250, 148, 0), // 40 db
            Color.rgb(249, 35, 11), // 50 db
            Color.rgb(202, 153, 180), // 60 db
            Color.rgb(154, 36, 224), // 70 db
            Color.rgb(132, 253, 255), // 80 db
            Color.rgb(161, 101, 73), // 90 db
            Color.rgb(118, 15, 6), // 95 db

    };

    protected int getColorFromTable(float power)
    {
        //int index = ((int)(power * 100.0)/5)-1;
        //int index = ((int)(power * 100))-1;
        //testing
        /*
        int index = ((int)(power));
        if (index < 0) { index = 0; }
        if (index == 0) { index = 0; }
        if (index == 1) { index = 1; }
        if (index == 2) { index = 2; }
        if (index == 3) { index = 3; }
        if (index == 4) { index = 4; }
        if (index == 5) { index = 5; }
        if (index == 6) { index = 6; }
        if (index == 7) { index = 7; }
        if (index == 8) { index = 8; }
        if (index == 9) { index = 9; }
        if (index == 10) { index = 10; }
        if (index == 11) { index = 11; }
        if (index == 12) { index = 12; }
        if (index == 13) { index = 13; }
        if (index == 14) { index = 14; }
        if (index == 15) { index = 15; }
        if (index == 16) { index = 16; }
        if (index == 17) { index = 17; }
        if (index == 18) { index = 18; }
        if (index == 19) { index = 19; }
        if (index == 20) { index = 20; }
        if (index == 21) { index = 21; }
        if (index == 22) { index = 22; }
        if (index == 23) { index = 23; }
        if (index == 24) { index = 24; }
        if (index == 25) { index = 25; }
        if (index == 26) { index = 26; }
        if (index == 27) { index = 27; }
        if (index == 28) { index = 28; }
        if (index == 29) { index = 29; }
        if (index == 30) { index = 30; }
        if (index == 31) { index = 31; }
        if (index == 32) { index = 32; }
        if (index == 33) { index = 33; }
        if (index == 34) { index = 34; }
        if (index == 35) { index = 35; }
        if (index == 36) { index = 36; }
        if (index == 37) { index = 37; }
        if (index == 38) { index = 38; }
        if (index == 39) { index = 39; }
        if (index == 40) { index = 40; }
        if (index == 41) { index = 41; }
        if (index == 42) { index = 42; }
        if (index == 43) { index = 43; }
        if (index == 44) { index = 44; }
        if (index == 45) { index = 45; }
        if (index == 46) { index = 46; }
        if (index == 47) { index = 47; }
        if (index == 48) { index = 48; }
        if (index == 49) { index = 49; }
        if (index == 50) { index = 50; }
        if (index == 51) { index = 51; }
        if (index == 52) { index = 52; }
        if (index == 53) { index = 53; }
        if (index == 54) { index = 54; }
        if (index == 55) { index = 55; }
        if (index == 56) { index = 56; }
        if (index == 57) { index = 57; }
        if (index == 58) { index = 58; }
        if (index == 59) { index = 59; }
        if (index == 60) { index = 60; }
        if (index == 61) { index = 61; }
        if (index == 62) { index = 62; }
        if (index == 63) { index = 63; }
        if (index == 64) { index = 64; }
        if (index == 65) { index = 65; }
        if (index == 66) { index = 66; }
        if (index == 67) { index = 67; }
        if (index == 68) { index = 68; }
        if (index == 69) { index = 69; }
        if (index == 70) { index = 70; }
        if (index == 71) { index = 71; }
        if (index == 72) { index = 72; }
        if (index == 73) { index = 73; }
        if (index == 74) { index = 74; }
        if (index == 80) { index = 80; }
        if (index == 95) { index = 95; }

        */
        ///if (index >= colorTable.length) { index = colorTable.length-1; }
        //if (index >= colorTable.length) { index = colorTable.length; }

        ///TODO DEBUG!!!
        return colorTable[(int)power+1];
    }
}
