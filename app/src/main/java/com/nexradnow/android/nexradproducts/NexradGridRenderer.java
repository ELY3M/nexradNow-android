package com.nexradnow.android.nexradproducts;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
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
                    int color = getColorFromTable(cellValue / 100.0f);
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

*/

    static int[] colorTable = {
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

    protected int getColorFromTable(float power)
    {
        int index = ((int)(power * 100.0)/1)-1;
        if (index < 0) { index = 0; }
        if (index >= colorTable.length) { index = colorTable.length-1;}
        return colorTable[index];
    }
}
