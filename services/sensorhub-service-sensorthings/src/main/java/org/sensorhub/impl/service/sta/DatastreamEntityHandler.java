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
import de.fraunhofer.iosb.ilt.frostserver.model.Thing;
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
import de.fraunhofer.iosb.ilt.frostserver.util.NoSuchEntityException;
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
    static final String NOT_FOUND_MESSAGE = "Cannot find 'Datastream' entity with ID #";
    static final String MISSING_ASSOC = "Missing reference to 'Datastream' entity";
    static final String UCUM_URI_PREFIX = "http://unitsofmeasure.org/ucum.html#";
    static final String BAD_LINK_THING = "A new Datastream SHALL link to an Thing entity";
        
    OSHPersistenceManager pm;
    IDataStreamStore dataStreamReadStore;
    ISTADataStreamStore dataStreamWriteStore;
    STASecurity securityHandler;
    int maxPageSize = 100;
    
    
    DatastreamEntityHandler(OSHPersistenceManager pm)
    {
        this.pm = pm;
        this.dataStreamWriteStore = pm.database != null ? pm.database.getDataStreamStore() : null;
        var federatedDataStreamStore = pm.obsDbRegistry.getFederatedObsDatabase().getObservationStore().getDataStreams();
        this.dataStreamReadStore = new STAFederatedDataStreamStoreWrapper(pm.database, federatedDataStreamStore);
        this.securityHandler = pm.service.getSecurityHandler();
    }
    
    
    @Override
    public ResourceId create(Entity entity) throws NoSuchEntityException
    {
        securityHandler.checkPermission(securityHandler.sta_insert_datastream);
        Asserts.checkArgument(entity instanceof AbstractDatastream);
        AbstractDatastream<?> dataStream = (AbstractDatastream<?>)entity;
        
        // handle associations / deep inserts
        ResourceId thingId = pm.thingHandler.handleThingAssoc(dataStream.getThing());
        ResourceId sensorId = pm.sensorHandler.handleSensorAssoc(dataStream.getSensor());
        if (dataStream instanceof Datastream)
            pm.obsPropHandler.handleObsPropertyAssoc(((Datastream)dataStream).getObservedProperty());
        
        // retrieve sensor proxy
        pm.sensorHandler.checkProcedureWritable(sensorId.internalID);
        VirtualSensorProxy sensorProxy = tryGetProcedureProxy(sensorId.internalID);
        
        // add data stream
        return addDatastream(thingId.internalID, sensorId.internalID, sensorProxy, dataStream);
    }
    
    
    protected ResourceId addDatastream(long thingID, long sensorID, VirtualSensorProxy sensorProxy, AbstractDatastream<?> dataStream) throws NoSuchEntityException
    {
        // add output to virtual sensor in registry
        DataRecord recordStruct = toSweCommon(dataStream);
        sensorProxy.newOutput(recordStruct, new TextEncodingImpl());
        
        if (dataStreamWriteStore != null)
        {
            // create data stream object
            DataStreamInfo dsInfo = new STADataStream.Builder()
                .withThing(thingID)
                .withProcedure(new FeatureId(
                    pm.toLocalID(sensorID),
                    sensorProxy.getUniqueIdentifier()))
                .withRecordDescription(recordStruct)
                .withRecordEncoding(new TextEncodingImpl())
                .build();
            
            // add to database and get its public ID
            Long newId = dataStreamWriteStore.add(dsInfo);
            ResourceId newDsId = new ResourceId(pm.toPublicID(newId));
            
            // handle associations / deep inserts            
            pm.observationHandler.handleObservationAssocList(newDsId, dataStream);
            return newDsId;
        }
        else
        {        
            // otherwise just get the datastream as exposed by procedure registry
            long publicID = dataStreamReadStore.getLatestVersionKey(
                sensorProxy.getUniqueIdentifier(),
                recordStruct.getName());
            
            return new ResourceId(publicID);
        }
    }
    

    @Override
    public boolean update(Entity entity) throws NoSuchEntityException
    {
        Asserts.checkArgument(entity instanceof AbstractDatastream);
        AbstractDatastream<?> dataStream = (AbstractDatastream<?>)entity;
        
        securityHandler.checkPermission(securityHandler.sta_update_datastream);
        
        // get existing data stream
        ResourceId dsId = (ResourceId)entity.getId();
        DataStreamInfo oldDsInfo = dataStreamReadStore.get(dsId.internalID);
        if (oldDsInfo == null)
            throw new NoSuchEntityException(NOT_FOUND_MESSAGE + dsId);
        
        // get sensor proxy from registry
        long procID = oldDsInfo.getProcedure().getInternalID();
        pm.sensorHandler.checkProcedureWritable(procID);
        VirtualSensorProxy sensorProxy = tryGetProcedureProxy(procID);
        DataRecord newRecordStruct = toSweCommon(dataStream);
        
        if (dataStreamWriteStore != null)
        {
            // store new data stream version
            DataStreamInfo dsInfo = new DataStreamInfo.Builder()
                .withProcedure(oldDsInfo.getProcedure())
                .withRecordDescription(newRecordStruct)
                .withRecordEncoding(oldDsInfo.getRecommendedEncoding())
                .withRecordVersion(oldDsInfo.getRecordVersion()+1)
                .build();
            
            // check name wasn't changed
            Asserts.checkArgument(dsInfo.getOutputName().equals(oldDsInfo.getOutputName()), "Cannot change a datastream name");
            dataStreamWriteStore.put(pm.toLocalID(dsId.internalID), dsInfo);
        }
        
        // update virtual sensor output in registry
        sensorProxy.newOutput(newRecordStruct, oldDsInfo.getRecommendedEncoding());
        
        return true;
    }
    
    
    public boolean patch(ResourceId id, JsonPatch patch) throws NoSuchEntityException
    {
        securityHandler.checkPermission(securityHandler.sta_update_sensor);
        throw new UnsupportedOperationException("Patch not supported");
    }
    
    
    public boolean delete(ResourceId id) throws NoSuchEntityException
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
            // need to convert to public ID if internalID was coming from write store
            long procID = dsInfo.getProcedure().getInternalID();
            if (dataStreamWriteStore != null)
                procID = pm.toPublicID(procID);
            
            VirtualSensorProxy sensorProxy = tryGetProcedureProxy(procID);
            AbstractProcess sensorDesc = sensorProxy.getCurrentDescription();
            sensorDesc.getOutputList().remove(dsInfo.getOutputName());
            sensorProxy.updateDescription(sensorDesc);
            return true;
        };
        
        throw new NoSuchEntityException(NOT_FOUND_MESSAGE + id);
    }
    
    
    protected VirtualSensorProxy tryGetProcedureProxy(long sensorId) throws NoSuchEntityException
    {
        return pm.sensorHandler.getProcedureProxy(sensorId);
    }
    

    @Override
    public AbstractDatastream getById(ResourceId id, Query q) throws NoSuchEntityException
    {
        securityHandler.checkPermission(securityHandler.sta_read_datastream);
        
        DataStreamInfo dsInfo = dataStreamReadStore.get(id.internalID);
        if (dsInfo == null)
            throw new NoSuchEntityException(NOT_FOUND_MESSAGE + id);

        return toFrostDatastream(id.internalID, dsInfo, q);
    }
    

    @Override
    @SuppressWarnings("unchecked")
    public EntitySet<?> queryCollection(ResourcePath path, Query q)
    {
        securityHandler.checkPermission(securityHandler.sta_read_datastream);
        
        DataStreamFilter filter = getFilter(path, q);
        int skip = q.getSkip(0);
        int limit = Math.min(q.getTopOrDefault(), maxPageSize);
        
        var entitySet = dataStreamReadStore.selectEntries(filter)
            .filter(e -> e.getValue().getRecommendedEncoding() instanceof TextEncoding)
            .skip(skip)
            .limit(limit+1) // request limit+1 elements to handle paging
            .map(e -> toFrostDatastream(e.getKey(), e.getValue(), q))
            .filter(ds -> ds.getEntityType() == path.getMainElementType())
            .collect(Collectors.toCollection(EntitySetImpl::new));
        
        return FrostUtils.handlePaging(entitySet, path, q, limit);
    }
    
    
    protected DataStreamFilter getFilter(ResourcePath path, Query q)
    {
        STADataStreamFilter.Builder builder = new STADataStreamFilter.Builder();
        
        EntityPathElement idElt = path.getIdentifiedElement();
        if (idElt != null)
        {
            if (idElt.getEntityType() == EntityType.THING)
            {
                ResourceId thingId = (ResourceId)idElt.getId();
                builder.withThings(thingId.internalID);
            }
            else if (idElt.getEntityType() == EntityType.SENSOR)
            {
                ResourceId sensorId = (ResourceId)idElt.getId();
                builder.withProcedures(sensorId.internalID);
            }
            else if (idElt.getEntityType() == EntityType.OBSERVATION)
            {
                CompositeResourceId obsId = (CompositeResourceId)idElt.getId();
                builder.withInternalIDs(obsId.parentIDs[0]);
            }
        }
        
        DatastreamFilterVisitor visitor = new DatastreamFilterVisitor(builder);
        if (q.getFilter() != null)
            q.getFilter().accept(visitor);
        
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
    
    
    protected AbstractDatastream toFrostDatastream(Long publicID, DataStreamInfo dsInfo, Query q)
    {
        AbstractDatastream dataStream;
        Set<Property> select = q != null ? q.getSelect() : Collections.emptySet();
        boolean isExternalDatastream = pm.obsDbRegistry.getDatabaseID(publicID) != pm.database.getDatabaseID();
        
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
            if (!isExternalDatastream)
                simpleDs.getObservedProperty().setExportObject(false);
        }
        else
        {
            MultiDatastream multiDs = new MultiDatastream();
            multiDs.setObservationType(IObservation.OBS_TYPE_RECORD);
            visitComponent(rec, multiDs, select.isEmpty() || select.contains(NavigationProperty.OBSERVEDPROPERTIES));
            dataStream = multiDs;
            if (!isExternalDatastream)
                multiDs.getObservedProperties().setExportObject(false);
        }
        
        // common properties
        dataStream.setId(new ResourceId(publicID));
        dataStream.setName(rec.getLabel() != null ? 
            rec.getLabel() : StringUtils.capitalize(rec.getName()));
        dataStream.setDescription(rec.getDescription());
        
        // link to Thing
        long thingID =  isExternalDatastream ? 
            STAService.HUB_THING_ID :
            dataStreamWriteStore.getAssociatedThing(pm.toLocalID(publicID));
        Thing thing = new Thing(new ResourceId(thingID));
        thing.setExportObject(false);
        dataStream.setThing(thing);
        
        // link to Sensor
        ResourceId sensorId = new ResourceId(dsInfo.getProcedure().getInternalID());
        Sensor sensor = new Sensor(sensorId);
        sensor.setExportObject(false);   
        dataStream.setSensor(sensor);
        
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
    
    
    protected ResourceId handleDatastreamAssoc(AbstractDatastream ds) throws NoSuchEntityException
    {
        Asserts.checkArgument(ds != null, MISSING_ASSOC);
        ResourceId dsId;        
                
        if (ds.getName() == null)
        {
            dsId = (ResourceId)ds.getId();
            Asserts.checkArgument(dsId != null, MISSING_ASSOC);
            checkDatastreamID(dsId.internalID);
        }
        else
        {
            // deep insert
            dsId = create(ds);
        }
        
        return dsId;
    }
    
    
    protected void handleDatastreamAssocList(ResourceId thingId, Thing thing) throws NoSuchEntityException
    {
        if (thing.getDatastreams() != null)
        {
            for (Datastream ds: thing.getDatastreams())
            {
                ds.setThing(new Thing(thingId));
                handleDatastreamAssoc(ds);
            }
        }
        
        if (thing.getMultiDatastreams() != null)
        {
            for (MultiDatastream ds: thing.getMultiDatastreams())
            {
                ds.setThing(new Thing(thingId));
                handleDatastreamAssoc(ds);
            }
        }
    }
    
    
    protected void handleDatastreamAssocList(ResourceId sensorId, Sensor sensor) throws NoSuchEntityException
    {
        if (sensor.getDatastreams() != null)
        {
            for (Datastream ds: sensor.getDatastreams())
            {
                ds.setSensor(new Sensor(sensorId));
                pm.dataStreamHandler.handleDatastreamAssoc(ds);
            }
        }
        
        if (sensor.getMultiDatastreams() != null)
        {
            for (MultiDatastream ds: sensor.getMultiDatastreams())
            {
                ds.setSensor(new Sensor(sensorId));
                pm.dataStreamHandler.handleDatastreamAssoc(ds);
            }
        }
    }
    
    
    /*
     * Check that sensorID is present in database and exposed by service
     */
    protected void checkDatastreamID(long publicID) throws NoSuchEntityException
    {
        DataStreamInfo dsInfo = dataStreamReadStore.get(publicID);
        boolean hasSensor = dsInfo != null && isDatastreamVisible(publicID, dsInfo.getProcedure());
        if (!hasSensor)
            throw new NoSuchEntityException(NOT_FOUND_MESSAGE + publicID);
    }
    
    
    protected boolean isDatastreamVisible(long publicID, FeatureId fid)
    {
        // TODO also check that current user has the right to read this entity!
        
        return pm.obsDbRegistry.getDatabaseID(publicID) == pm.database.getDatabaseID() ||
            pm.service.isProcedureExposed(fid);
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
