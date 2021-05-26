package org.sensorhub.impl.sensor.isa;

import org.sensorhub.api.data.DataEvent;
import org.vast.swe.SWEConstants;


public class AtmosWindOutput extends ISAOutput
{
    static final String[] WIND_CLASSES = {
        "UNKNOWN", "CONSTANT", "GUST", "LIGHT TURBULENCE",
        "MODERATE TURBULENCE", "SEVERE TURBULENCE", "EXTREME TURBULENCE",
        "SQUALL", "VARIABLE", "UNSPECIFIED"};
    
    
    public AtmosWindOutput(ISASensor parentSensor)
    {
        super("wind", parentSensor);
        ISAHelper isa = new ISAHelper();
        
        // output structure
        dataStruct = isa.createRecord()
            .name(this.name)
            .definition(ISAHelper.ISA_DEF_URI_BASE + "Atmospheric_Wind")
            .label("Atmospheric Wind")
            .addSamplingTimeIsoUTC("time")
            .addField("category", isa.createCategory()
                .definition(ISAHelper.ISA_DEF_URI_BASE + "Wind_Category")
                .description("The type of precipitation observed.")
                .addAllowedValues(WIND_CLASSES)
                .addNilValue(WIND_CLASSES[0], SWEConstants.NIL_UNKNOWN))
            .addField("speed", isa.createQuantity()
                .definition(ISAHelper.MMI_CF_DEF_URI_BASE + "wind_speed")
                .description("Measured prevailing speed of the wind. If not provided, the default value is 0.")
                .uomCode("m/s")
                .addNilValue(Double.NaN, SWEConstants.NIL_UNKNOWN))
            .addField("direction", isa.createQuantity()
                .description("Direction of the wind in the x,y axis using the reference frame NOLL (North Oriented, Local-Level).")
                .refFrame(SWEConstants.REF_FRAME_NED)
                .definition(ISAHelper.MMI_CF_DEF_URI_BASE + "wind_from_direction")
                .uomCode("deg")
                .addNilValue(Double.NaN, SWEConstants.NIL_UNKNOWN))
            .build();
                
        // default output encoding
        dataEnc = isa.newTextEncoding(",", "\n");
    }
    
    
    @Override
    public double getAverageSamplingPeriod()
    {
        return 15.;
    }


    protected long nextRecordTime = Long.MIN_VALUE;
    protected void sendRandomMeasurement()
    {
        var now = System.currentTimeMillis();
        if (nextRecordTime > now)
            return;
        
        var dataBlk = dataStruct.createDataBlock();
        
        int i = 0;        
        dataBlk.setDoubleValue(i++, ((double)now)/1000.);
        dataBlk.setStringValue(i++, WIND_CLASSES[(int)(Math.random()*WIND_CLASSES.length)]); // wind class
        dataBlk.setDoubleValue(i++, (int)(Math.random()*100) / 10.); // speed
        dataBlk.setDoubleValue(i++, (int)(Math.random()*360)); // dir
        
        nextRecordTime = now + (long)(getAverageSamplingPeriod()*1000);
        latestRecordTime = now;
        latestRecord = dataBlk;
        eventHandler.publish(new DataEvent(latestRecordTime, this, dataBlk));
    }
}
