/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sta;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.sensorhub.api.datastore.DataStreamFilter;
import org.sensorhub.api.datastore.DataStreamInfo;
import org.sensorhub.api.datastore.FeatureId;
import org.sensorhub.api.datastore.IDataStreamStore;
import org.sensorhub.impl.sensor.VirtualSensorProxy;
import org.vast.data.TextEncodingImpl;
import org.vast.ogc.om.IObservation;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.unit.Unit;
import org.vast.util.Asserts;
import com.github.fge.jsonpatch.JsonPatch;
import de.fraunhofer.iosb.ilt.frostserver.model.Datastream;
import de.fraunhofer.iosb.ilt.frostserver.model.MultiDatastream;
import de.fraunhofer.iosb.ilt.frostserver.model.ObservedProperty;
import de.fraunhofer.iosb.ilt.frostserver.model.Sensor;
import de.fraunhofer.iosb.ilt.frostserver.model.core.AbstractDatastream;
import de.fraunhofer.iosb.ilt.frostserver.model.core.Entity;
import de.fraunhofer.iosb.ilt.frostserver.model.core.EntitySet;
import de.fraunhofer.iosb.ilt.frostserver.model.core.EntitySetImpl;
import de.fraunhofer.iosb.ilt.frostserver.model.ext.UnitOfMeasurement;
import de.fraunhofer.iosb.ilt.frostserver.path.EntityPathElement;
import de.fraunhofer.iosb.ilt.frostserver.path.EntityType;
import de.fraunhofer.iosb.ilt.frostserver.path.NavigationProperty;
import de.fraunhofer.iosb.ilt.frostserver.path.Property;
import de.fraunhofer.iosb.ilt.frostserver.path.ResourcePath;
import de.fraunhofer.iosb.ilt.frostserver.query.Query;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.swe.v20.Category;
import net.opengis.swe.v20.Count;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.HasUom;
import net.opengis.swe.v20.Quantity;
import net.opengis.swe.v20.ScalarComponent;
import net.opengis.swe.v20.TextEncoding;
import net.opengis.swe.v20.Vector;


/**
 * <p>
 * Service handler for Sensor resources
 * </p>
 *
 * @author Alex Robin
 * @date Sep 7, 2019
 */
@SuppressWarnings("rawtypes")
public class DatastreamEntityHandler implements IResourceHandler<AbstractDatastream>
{
    static final String UCUM_URI_PREFIX = "http://unitsofmeasure.org/ucum.html#";
        
    OSHPersistenceManager pm;
    IDataStreamStore dataStreamReadStore;
    IDataStreamStore dataStreamWriteStore;
    STASecurity securityHandler;
    int maxPageSize = 100;
    
    
    DatastreamEntityHandler(OSHPersistenceManager pm)
    {
        this.pm = pm;
        this.dataStreamReadStore = pm.obsDbRegistry.getObservationStore().getDataStreams();
        this.dataStreamWriteStore = pm.obsDatabase != null ? pm.obsDatabase.getObservationStore().getDataStreams() : null;
        this.securityHandler = pm.service.getSecurityHandler();
    }
    
    
    @Override
    public ResourceId create(Entity entity)
    {
        securityHandler.checkPermission(securityHandler.sta_insert_datastream);
        Asserts.checkArgument(entity instanceof AbstractDatastream);
        AbstractDatastream<?> dataStream = (AbstractDatastream<?>)entity;
        
        // check sensor is present
        Sensor sensor = dataStream.getSensor();
        if (sensor == null)
            throw new IllegalArgumentException("A new Datastream SHALL link to an Sensor entity");
        ResourceId sensorId = (ResourceId)dataStream.getSensor().getId();
        VirtualSensorProxy sensorProxy = tryGetProcedureProxy(sensorId.internalID);
        
        // add data stream
        Long key = addDatastream(sensorId.internalID, sensorProxy, dataStream);
        return new ResourceId(key);
    }
    
    
    protected Long addDatastream(Long sensorID, VirtualSensorProxy sensorProxy, AbstractDatastream dataStream)
    {
        // add output to virtual sensor in registry
        DataRecord recordStruct = toSweCommon(dataStream);
        sensorProxy.newOutput(recordStruct, new TextEncodingImpl());
        
        if (dataStreamWriteStore != null)
        {
            // also create data stream entry in database
            DataStreamInfo dsInfo = DataStreamInfo.builder()
                .withProcedure(new FeatureId(
                    pm.toLocalID(sensorID),
                    sensorProxy.getUniqueIdentifier()))
                .withRecordDescription(recordStruct)
                .withRecordEncoding(new TextEncodingImpl())
                .build();
            
            dataStreamWriteStore.add(dsInfo);
        }
        
        return dataStreamReadStore.getLastKey(
            sensorProxy.getUniqueIdentifier(),
            recordStruct.getName());
    }
    

    @Override
    public boolean update(Entity entity)
    {
        Asserts.checkArgument(entity instanceof AbstractDatastream);
        AbstractDatastream<?> dataStream = (AbstractDatastream<?>)entity;
        
        securityHandler.checkPermission(securityHandler.sta_update_datastream);
        
        // get existing data stream
        ResourceId dsId = (ResourceId)entity.getId();
        DataStreamInfo oldDsInfo = dataStreamReadStore.get(dsId.internalID);
        if (oldDsInfo == null)
            return false;
        
        // get sensor proxy from registry
        long sensorId = oldDsInfo.getProcedure().getInternalID();
        VirtualSensorProxy sensorProxy = tryGetProcedureProxy(sensorId);
        DataRecord newRecordStruct = toSweCommon(dataStream);
        
        if (dataStreamWriteStore != null)
        {
            // store new data stream version
            DataStreamInfo dsInfo = DataStreamInfo.builder()
                .withProcedure(oldDsInfo.getProcedure())
                .withRecordDescription(newRecordStruct)
                .withRecordEncoding(oldDsInfo.getRecommendedEncoding())
                .withRecordVersion(oldDsInfo.getRecordVersion()+1)
                .build();
            
            // check name wasn't changed
            Asserts.checkArgument(dsInfo.getOutputName().equals(oldDsInfo.getOutputName()), "Cannot change a data stream name");
            dataStreamWriteStore.put(pm.toLocalID(dsId.internalID), dsInfo);
        }
        
        // update virtual sensor output in registry
        sensorProxy.newOutput(newRecordStruct, oldDsInfo.getRecommendedEncoding());
        
        return true;
    }
    
    
    public boolean patch(ResourceId id, JsonPatch patch)
    {
        securityHandler.checkPermission(securityHandler.sta_update_sensor);
        throw new UnsupportedOperationException("Patch not supported");
    }
    
    
    public boolean delete(ResourceId id)
    {
        securityHandler.checkPermission(securityHandler.sta_delete_datastream);
        
        DataStreamInfo dsInfo;
        if (dataStreamWriteStore != null)
            dsInfo = dataStreamWriteStore.remove(pm.toLocalID(id.internalID));
        else
            dsInfo = dataStreamReadStore.get(id.internalID);
        
        // also update sensor description in registry
        if (dsInfo != null)
        {    
            VirtualSensorProxy sensorProxy = tryGetProcedureProxy(dsInfo.getProcedure().getInternalID());
            AbstractProcess sensorDesc = sensorProxy.getCurrentDescription();
            sensorDesc.getOutputList().remove(dsInfo.getOutputName());
            sensorProxy.updateDescription(sensorDesc);
            return true;
        };
        
        return false;
    }
    
    
    protected VirtualSensorProxy tryGetProcedureProxy(long sensorId)
    {
        return pm.sensorHandler.getProcedureProxy(sensorId);
    }
    

    @Override
    public AbstractDatastream getById(ResourceId id, Query q)
    {
        securityHandler.checkPermission(securityHandler.sta_read_datastream);
        
        DataStreamInfo dsInfo = dataStreamReadStore.get(id.internalID);
        if (dsInfo == null)
            return null;

        return toFrostDatastream(id.internalID, dsInfo, q);
    }
    

    @Override
    public EntitySet<?> queryCollection(ResourcePath path, Query q)
    {
        securityHandler.checkPermission(securityHandler.sta_read_datastream);
        
        DataStreamFilter filter = getFilter(path, q);
        int skip = q.getSkip(0);
        int limit = Math.min(q.getTopOrDefault(), maxPageSize);
        
        return dataStreamReadStore.selectEntries(filter)
            .filter(e -> e.getValue().getRecommendedEncoding() instanceof TextEncoding)
            .skip(skip)
            .limit(limit)
            .map(e -> toFrostDatastream(e.getKey(), e.getValue(), q))
            .filter(ds -> ds.getEntityType() == path.getMainElementType())
            .collect(Collectors.toCollection(EntitySetImpl::new));
    }
    
    
    protected DataStreamFilter getFilter(ResourcePath path, Query q)
    {
        DataStreamFilter.Builder builder = DataStreamFilter.builder();
        
        EntityPathElement idElt = path.getIdentifiedElement();
        if (idElt != null)
        {
            if (idElt.getEntityType() == EntityType.SENSOR)
            {
                ResourceId sensorId = (ResourceId)idElt.getId();
                builder.withProcedures(sensorId.internalID);
            }
            else if (idElt.getEntityType() == EntityType.OBSERVATION)
            {
                ObsResourceId obsId = (ObsResourceId)idElt.getId();
                builder.withInternalIDs(obsId.dataStreamID);
            }
        }
        
        /*SensorFilterVisitor visitor = new SensorFilterVisitor(builder);
        if (q.getFilter() != null)
            q.getFilter().accept(visitor);*/
        
        return builder.build();            
    }
    
    
    protected boolean isScalarOutput(DataComponent rec)
    {
        return (rec.getComponentCount() <= 2 &&
            rec.getComponent(1) instanceof ScalarComponent);
    }
    
    
    protected DataRecord toSweCommon(AbstractDatastream<?> abstractDs)
    {
        SWEHelper helper = new SWEHelper();
        
        DataRecord rec = helper.newDataRecord();
        rec.setName(toNCName(abstractDs.getName()));
        rec.setLabel(abstractDs.getName());
        rec.setDescription(abstractDs.getDescription());
        rec.addComponent("time", helper.newTimeIsoUTC(SWEConstants.DEF_PHENOMENON_TIME, "Sampling Time", null));
        
        if (abstractDs instanceof Datastream)
        {
            Datastream ds = (Datastream)abstractDs;
            Asserts.checkArgument(ds.getObservedProperty().getName() != null, "Observed properties must be provided inline when creating a datastream");
            
            DataComponent comp = toComponent(
                ds.getObservationType(),
                ds.getObservedProperty(),
                ds.getUnitOfMeasurement(),
                helper);
            
            rec.addComponent(comp.getName(), comp);
        }
        else
        {
            MultiDatastream ds = (MultiDatastream)abstractDs;
            
            int i = 0;
            for (ObservedProperty obsProp: ds.getObservedProperties())
            {
                Asserts.checkArgument(obsProp.getName() != null, "Observed properties must be provided inline when creating a datastream");
                
                DataComponent comp = toComponent(
                    ds.getMultiObservationDataTypes().get(i),
                    obsProp,
                    ds.getUnitOfMeasurements().get(i),
                    helper);
                i++;
                
                rec.addComponent(comp.getName(), comp);
            }
        }
        
        return rec;
    }
    
    
    protected DataComponent toComponent(String obsType, ObservedProperty obsProp, UnitOfMeasurement uom, SWEHelper fac)
    {
        DataComponent comp = null;
        
        if (IObservation.OBS_TYPE_MEAS.equals(obsType))
        {
            comp = fac.newQuantity();
            
            if (uom.getDefinition() != null && uom.getDefinition().startsWith(UCUM_URI_PREFIX))
                ((Quantity)comp).getUom().setCode(uom.getDefinition().replace(UCUM_URI_PREFIX, ""));
            else
                ((Quantity)comp).getUom().setHref(uom.getDefinition());
        }
        else if (IObservation.OBS_TYPE_CATEGORY.equals(obsType))
            comp = fac.newCategory();
        else if (IObservation.OBS_TYPE_COUNT.equals(obsType))
            comp = fac.newCount();
        else if (IObservation.OBS_TYPE_RECORD.equals(obsType))
            comp = fac.newDataRecord();
        
        if (comp != null)
        {
            comp.setName(toNCName(obsProp.getName()));
            comp.setLabel(obsProp.getName());
            comp.setDescription(obsProp.getDescription());
            comp.setDefinition(obsProp.getDefinition());
        }
        
        return comp;
    }
    
    
    protected String toNCName(String name)
    {
        return name.toLowerCase().replaceAll("\\s+", "_");
    }
    
    
    protected AbstractDatastream toFrostDatastream(Long internalID, DataStreamInfo dsInfo, Query q)
    {
        AbstractDatastream dataStream;
        Set<Property> select = q != null ? q.getSelect() : Collections.emptySet();
        
        // convert to simple or multi datastream
        DataComponent rec = dsInfo.getRecordDescription();
        if (isScalarOutput(rec))
        {
            Datastream simpleDs = new Datastream();
            DataComponent comp = rec.getComponent(1);
            
            simpleDs.setObservationType(toObsType(comp));
            simpleDs.setUnitOfMeasurement(toUom(comp));
            if (select.isEmpty() || select.contains(NavigationProperty.OBSERVEDPROPERTY))
                simpleDs.setObservedProperty(toObservedProperty(comp, Collections.emptySet()));
            
            dataStream = simpleDs;
        }
        else
        {
            MultiDatastream multiDs = new MultiDatastream();
            multiDs.setObservationType(IObservation.OBS_TYPE_RECORD);
            visitComponent(rec, multiDs, select.isEmpty() || select.contains(NavigationProperty.OBSERVEDPROPERTIES));
            dataStream = multiDs;
        }
        
        // common properties
        dataStream.setId(new ResourceId(internalID));
        dataStream.setName(rec.getLabel() != null ? 
            rec.getLabel() : StringUtils.capitalize(rec.getName()));
        dataStream.setDescription(rec.getDescription());
        if (select.isEmpty() || select.contains(NavigationProperty.SENSOR))
        {
            ResourceId sensorId = new ResourceId(dsInfo.getProcedure().getInternalID());
            dataStream.setSensor(new Sensor(sensorId));
        }
        
        return dataStream;
    }
    
    
    protected void visitComponent(DataComponent c, MultiDatastream multiDs, boolean expandObsProps)
    {
        if (c instanceof ScalarComponent)
        {
            String def = c.getDefinition();
            
            // skip time stamp
            if (def != null && (SWEConstants.DEF_PHENOMENON_TIME.equals(def) ||
                SWEConstants.DEF_SAMPLING_TIME.equals(def)))
                return;
            
            multiDs.getMultiObservationDataTypes().add(toObsType(c));
            multiDs.getUnitOfMeasurements().add(toUom(c));            
            if (expandObsProps)
                multiDs.getObservedProperties().add(toObservedProperty(c, Collections.emptySet()));                        
        }
        else if (c instanceof DataRecord || c instanceof Vector)
        {
            for (int i = 0; i < c.getComponentCount(); i++)
            {
                DataComponent child = c.getComponent(i);
                visitComponent(child, multiDs, expandObsProps);
            }
        }
    }
    
    
    protected String toObsType(DataComponent comp)
    {
        if (comp instanceof Quantity)
            return IObservation.OBS_TYPE_MEAS;
        else if (comp instanceof Category)
            return IObservation.OBS_TYPE_CATEGORY;
        else if (comp instanceof Count)
            return IObservation.OBS_TYPE_COUNT;
        else if (comp instanceof DataRecord)
            return IObservation.OBS_TYPE_RECORD;
        else
            return null;
    }
    
    
    protected ObservedProperty toObservedProperty(DataComponent comp, Set<Property> select)
    {
        ObservedProperty obsProp = new ObservedProperty();
        
        obsProp.setDefinition(comp.getDefinition());
        obsProp.setName(comp.getLabel() != null ? 
            comp.getLabel() : StringUtils.capitalize(comp.getName()));
        obsProp.setDescription(comp.getDescription());
        
        return obsProp;
    }
    
    
    protected UnitOfMeasurement toUom(DataComponent comp)
    {
        UnitOfMeasurement uom = new UnitOfMeasurement();
        
        if (comp instanceof HasUom)
        {
            if (((HasUom)comp).getUom().hasHref())
            {
                uom.setDefinition(((HasUom)comp).getUom().getHref());
            }
            else
            {                
                Unit ucumUnit = ((HasUom)comp).getUom().getValue();
                uom.setName(ucumUnit.getName());
                uom.setSymbol(ucumUnit.getPrintSymbol());
                uom.setDefinition(UCUM_URI_PREFIX + 
                    (ucumUnit.getCode() != null ? ucumUnit.getCode() : ucumUnit.getExpression()));
            }
        }
        else if (comp instanceof Count)
        {
            uom.setName("Count");
        }
        else
        {
            uom.setName("No Unit");
        }
        
        return uom;
    }
    
    
    /*
     * Helper methods to convert to/from packed numerical IDs
     */    
    /*static int MAX_DATASTREAMS_PER_SENSOR = 100;
    static int MAX_VERSIONS_PER_DATASTREAM = 100;
    static int MAX_VERSIONS_PER_SENSOR = MAX_DATASTREAMS_PER_SENSOR * MAX_VERSIONS_PER_DATASTREAM;
    static int LATEST_VERSION = 999;
    
    static class DatastreamIds
    {
        long sensorId;
        int outputId;
        int version;
    }
    
    
    protected ResourceId generateDatastreamId(long sensorId, int outputId, int version)
    {
        long sensorIdPart = sensorId * MAX_VERSIONS_PER_SENSOR;
        long outputIdPart = outputId * MAX_VERSIONS_PER_DATASTREAM;
        return new ResourceId(sensorIdPart + outputIdPart + version);
    }
    
    
    protected DatastreamIds parseDatastreamId(ResourceId dsId)
    {
        long internalId = dsId.getValue();
        DatastreamIds ids = new DatastreamIds();
        ids.sensorId = internalId / MAX_VERSIONS_PER_SENSOR;
        ids.outputId = (int)((internalId / MAX_VERSIONS_PER_DATASTREAM) % MAX_DATASTREAMS_PER_SENSOR);
        ids.version = (int)(internalId % MAX_VERSIONS_PER_DATASTREAM);
        return ids;
    }*/

}