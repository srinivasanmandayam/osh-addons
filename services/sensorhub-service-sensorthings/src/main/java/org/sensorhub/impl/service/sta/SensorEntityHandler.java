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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import org.isotc211.v2005.gmd.CIOnlineResource;
import org.isotc211.v2005.gmd.impl.GMDFactory;
import org.sensorhub.api.datastore.obs.DataStreamFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.IObsStore;
import org.sensorhub.api.datastore.procedure.IProcedureStore;
import org.sensorhub.api.datastore.procedure.ProcedureFilter;
import org.sensorhub.api.event.EventUtils;
import org.sensorhub.api.event.IEventPublisher;
import org.sensorhub.api.obs.IDataStreamInfo;
import org.sensorhub.api.procedure.IProcedureWithDesc;
import org.sensorhub.api.procedure.IProcedureRegistry;
import org.sensorhub.api.procedure.ProcedureAddedEvent;
import org.sensorhub.api.procedure.ProcedureChangedEvent;
import org.sensorhub.api.procedure.ProcedureId;
import org.sensorhub.api.procedure.ProcedureRemovedEvent;
import org.vast.ogc.om.IProcedure;
import org.vast.sensorML.SMLHelper;
import org.vast.util.Asserts;
import org.vast.util.TimeExtent;
import com.github.fge.jsonpatch.JsonPatch;
import com.google.common.base.Strings;
import de.fraunhofer.iosb.ilt.frostserver.model.Datastream;
import de.fraunhofer.iosb.ilt.frostserver.model.MultiDatastream;
import de.fraunhofer.iosb.ilt.frostserver.model.Sensor;
import de.fraunhofer.iosb.ilt.frostserver.model.core.Entity;
import de.fraunhofer.iosb.ilt.frostserver.model.core.EntitySet;
import de.fraunhofer.iosb.ilt.frostserver.model.core.EntitySetImpl;
import de.fraunhofer.iosb.ilt.frostserver.path.EntityPathElement;
import de.fraunhofer.iosb.ilt.frostserver.path.EntityType;
import de.fraunhofer.iosb.ilt.frostserver.path.NavigationProperty;
import de.fraunhofer.iosb.ilt.frostserver.path.ResourcePath;
import de.fraunhofer.iosb.ilt.frostserver.query.Expand;
import de.fraunhofer.iosb.ilt.frostserver.query.Query;
import de.fraunhofer.iosb.ilt.frostserver.util.NoSuchEntityException;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.sensorml.v20.DocumentList;


/**
 * <p>
 * Handler for Sensor resources
 * </p>
 *
 * @author Alex Robin
 * @date Sep 7, 2019
 */
public class SensorEntityHandler implements IResourceHandler<Sensor>
{
    static final String NOT_FOUND_MESSAGE = "Cannot find 'Sensor' entity with ID #";
    static final String NOT_WRITABLE_MESSAGE = "Cannot modify read-only 'Sensor' entity #";
    static final String MISSING_ASSOC = "Missing reference to 'Sensor' entity";
    static final String FORMAT_SML2 = "http://www.opengis.net/sensorml-json/2.0";

    OSHPersistenceManager pm;
    IProcedureStore procReadStore;
    IProcedureStore procWriteStore;
    IObsStore obsReadStore;
    STASecurity securityHandler;
    int maxPageSize = 100;
    ProcedureId procGroupID;


    SensorEntityHandler(OSHPersistenceManager pm)
    {
        this.pm = pm;
        this.procReadStore = pm.readDatabase.getProcedureStore();
        this.procWriteStore = pm.writeDatabase != null ? pm.writeDatabase.getProcedureStore() : null;
        this.obsReadStore = pm.readDatabase.getObservationStore();
        this.securityHandler = pm.service.getSecurityHandler();
        this.procGroupID = pm.service.getProcedureGroupID();
    }


    @Override
    public ResourceId create(@SuppressWarnings("rawtypes") Entity entity) throws NoSuchEntityException
    {
        checkTransactionsEnabled();
        Asserts.checkArgument(entity instanceof Sensor);
        Sensor sensor = (Sensor)entity;
        
        securityHandler.checkPermission(securityHandler.sta_insert_sensor);
        
        // generate unique ID from name
        Asserts.checkArgument(!Strings.isNullOrEmpty(sensor.getName()), "Sensor name must be set");        
        String procUID = procGroupID.getUniqueID() + ":" + sensor.getName().toLowerCase().replaceAll("\\s+", "_");
        
        try
        {
            return pm.writeDatabase.executeTransaction(() -> {
                
                // generate sensorML description
                AbstractProcess procDesc = toSmlProcess(sensor, procUID);
                
                // store in DB
                long publicSensorID;
                try
                {
                    var key = procWriteStore.add(procGroupID.getInternalID(), procDesc);
                    publicSensorID = pm.toPublicID(key.getInternalID());
                }
                catch (Exception e)
                {
                    throw new IllegalArgumentException("Sensor with name '" + sensor.getName() + "' already exists");
                }
                
                // publish event
                IEventPublisher publisher = pm.eventBus.getPublisher(IProcedureRegistry.EVENT_SOURCE_INFO);
                publisher.publish(new ProcedureAddedEvent(new ProcedureId(publicSensorID, procUID), procGroupID));
                
                // handle associations / deep inserts
                ResourceId newSensorId = new ResourceIdLong(publicSensorID);
                pm.dataStreamHandler.handleDatastreamAssocList(newSensorId, sensor);
    
                return newSensorId;
            });
        }
        catch (IllegalArgumentException | NoSuchEntityException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ServerErrorException("Error creating sensor", e);
        }
    }


    @Override
    public boolean update(@SuppressWarnings("rawtypes") Entity entity) throws NoSuchEntityException
    {
        checkTransactionsEnabled();
        Asserts.checkArgument(entity instanceof Sensor);
        Sensor sensor = (Sensor)entity;
        
        securityHandler.checkPermission(securityHandler.sta_update_sensor);
        
        ResourceId id = (ResourceId)entity.getId();
        long publicSensorID = ((ResourceId)entity.getId()).asLong();
        checkProcedureWritable(publicSensorID);
        
        // get current version
        long localSensorID = pm.toLocalID(publicSensorID);
        IProcedure proc = procWriteStore.getCurrentVersion(localSensorID);
        if (proc == null)
            throw new NoSuchEntityException(NOT_FOUND_MESSAGE + id);
                
        // update description
        try
        {
            return pm.writeDatabase.executeTransaction(() -> {
                
                // generate sensorML description
                String procUID = proc.getUniqueIdentifier();
                AbstractProcess procDesc = toSmlProcess((Sensor)entity, procUID);
                
                // update description in DB
                procWriteStore.add(procGroupID.getInternalID(), procDesc);
                
                // publish event
                IEventPublisher publisher = pm.eventBus.getPublisher(EventUtils.getProcedureEventSourceInfo(procUID));
                publisher.publish(new ProcedureChangedEvent(new ProcedureId(publicSensorID, procUID)));
                
                // handle associations / deep inserts
                pm.dataStreamHandler.handleDatastreamAssocList(id, sensor);
                
                return true;
            });
        }
        catch (IllegalArgumentException | NoSuchEntityException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ServerErrorException("Error updating sensor " + id, e);
        }
    }


    @Override
    public boolean patch(ResourceId id, JsonPatch patch) throws NoSuchEntityException
    {
        securityHandler.checkPermission(securityHandler.sta_update_sensor);
        throw new UnsupportedOperationException("Patch not supported");
    }


    @Override
    public boolean delete(ResourceId id) throws NoSuchEntityException
    {
        checkTransactionsEnabled();
        securityHandler.checkPermission(securityHandler.sta_delete_sensor);
        
        long publicSensorID = id.asLong();
        checkProcedureWritable(publicSensorID);
        
        // get current version
        long localSensorID = pm.toLocalID(publicSensorID);
        IProcedure proc = procWriteStore.getCurrentVersion(localSensorID);
        if (proc == null)
            throw new NoSuchEntityException(NOT_FOUND_MESSAGE + id);
                
        try
        {
            return pm.writeDatabase.executeTransaction(() -> {
                                
                // delete all attached datastreams
                pm.dataStreamHandler.dataStreamWriteStore.removeEntries(new DataStreamFilter.Builder()
                    .withProcedures(localSensorID)
                    .withAllVersions()
                    .build());
                
                // delete entire procedure history  
                procWriteStore.removeEntries(new ProcedureFilter.Builder()
                    .withInternalIDs(localSensorID)
                    .withAllVersions()
                    .build());
                
                // publish event
                var procID = new ProcedureId(publicSensorID, proc.getUniqueIdentifier());
                IEventPublisher publisher = pm.eventBus.getPublisher(EventUtils.getProcedureEventSourceInfo(procID.getUniqueID()));
                publisher.publish(new ProcedureRemovedEvent(procID, procGroupID));
    
                return true;
            });
        }
        catch (IllegalArgumentException | NoSuchEntityException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new ServerErrorException("Error deleting sensor " + id, e);
        }
    }


    @Override
    public Sensor getById(ResourceId id, Query q) throws NoSuchEntityException
    {
        securityHandler.checkPermission(securityHandler.sta_read_sensor);

        var proc = procReadStore.getCurrentVersion(id.asLong());
        if (proc == null || !isSensorVisible(id.asLong()))
            throw new NoSuchEntityException(NOT_FOUND_MESSAGE + id);
        
        return toFrostSensor(id.asLong(), proc, q);
    }


    @Override
    public EntitySet<Sensor> queryCollection(ResourcePath path, Query q)
    {
        securityHandler.checkPermission(securityHandler.sta_read_sensor);

        ProcedureFilter filter = getFilter(path, q);
        int skip = q.getSkip(0);
        int limit = Math.min(q.getTopOrDefault(), maxPageSize);

        var entitySet = procReadStore.selectEntries(filter)
            .filter(e -> isSensorVisible(e.getKey().getInternalID()))
            //.peek(e -> System.out.println(e.getValue().getUniqueIdentifier() + ": " + e.getValue().getValidTime()))
            .skip(skip)
            .limit(limit+1) // request limit+1 elements to handle paging
            .map(e -> toFrostSensor(e.getKey().getInternalID(), e.getValue(), q))
            .collect(Collectors.toCollection(EntitySetImpl::new));

        return FrostUtils.handlePaging(entitySet, path, q, limit);
    }


    protected ProcedureFilter getFilter(ResourcePath path, Query q)
    {
        ProcedureFilter.Builder builder = new ProcedureFilter.Builder()
            .validAtTime(Instant.now());

        ProcedureId procGroupID = pm.service.getProcedureGroupID();
        if (procGroupID != null)
            builder.withParents().withUniqueIDs(procGroupID.getUniqueID()).done();

        EntityPathElement idElt = path.getIdentifiedElement();
        if (idElt != null)
        {
            var parentElt = (EntityPathElement)path.getMainElement().getParent();

            // if direct parent is identified
            if (idElt == parentElt)
            {
                if (idElt.getEntityType() == EntityType.DATASTREAM ||
                    idElt.getEntityType() == EntityType.MULTIDATASTREAM)
                {
                    ResourceId dsId = (ResourceId)idElt.getId();
                    var dsKey = new DataStreamKey(dsId.asLong());
                    IDataStreamInfo dsInfo = obsReadStore.getDataStreams().get(dsKey);
                    builder.withInternalIDs(dsInfo.getProcedureID().getInternalID());
                }
            }

            // if direct parent is not identified, need to look it up
            else
            {
                if (parentElt.getEntityType() == EntityType.DATASTREAM ||
                    parentElt.getEntityType() == EntityType.MULTIDATASTREAM)
                {
                    var dataStreamSet = pm.dataStreamHandler.queryCollection(getParentPath(path), q);
                    builder.withInternalIDs(dataStreamSet.isEmpty() ?
                        Long.MAX_VALUE :
                        ((ResourceId)dataStreamSet.iterator().next().getId()).asLong());
                }
            }
        }

        SensorFilterVisitor visitor = new SensorFilterVisitor(builder);
        if (q.getFilter() != null)
            q.getFilter().accept(visitor);

        return builder.build();
    }


    protected AbstractProcess toSmlProcess(Sensor sensor, String uid)
    {
        // TODO use provided SensorML doc in metadata

        // create simple SensorML instance
        var proc = new SMLHelper().createPhysicalSystem()
            .uniqueID(uid)
            .name(sensor.getName())
            .description(sensor.getDescription())
            .validFrom(OffsetDateTime.now())
            .build();

        // get documentation link if set
        if (sensor.getMetadata() instanceof String)
        {
            CIOnlineResource doc = new GMDFactory().newCIOnlineResource();
            doc.setProtocol(sensor.getEncodingType());
            doc.setLinkage((String)sensor.getMetadata());
            
            DocumentList docList = new SMLHelper().createDocumentList()
                .add(doc)
                .build();
                
            docList.addDocument(doc);
            proc.getDocumentationList().add("sta_metadata", docList);
        }

        return proc;
    }

    public static class SensorMLMetadata
    {
        String uid;
        Instant validTimeBegin;
        Instant validTimeEnd;
    }


    @SuppressWarnings("unchecked")
    protected Sensor toFrostSensor(long internalId, IProcedureWithDesc proc, Query q)
    {
        // TODO add full SensorML doc in metadata

        Sensor sensor = new Sensor();
        sensor.setId(new ResourceIdLong(internalId));
        sensor.setName(proc.getName());
        sensor.setDescription(proc.getDescription());
        
        //if (q.getSelect().contains("metadata"))
        {
            AbstractProcess fullDesc = proc.getFullDescription();
            if (fullDesc != null)
            {    
                // add metadata link
                if (!fullDesc.getDocumentationList().isEmpty())
                {
                    DocumentList docList = fullDesc.getDocumentationList().get("sta_metadata");
                    if (!docList.getDocumentList().isEmpty())
                    {
                        CIOnlineResource doc = docList.getDocumentList().get(0);
                        sensor.setEncodingType(doc.getProtocol());
                        sensor.setMetadata(doc.getLinkage());
                    }
                }
                else
                {
                    var metadata = new SensorMLMetadata();
                    metadata.uid = proc.getUniqueIdentifier();
                    TimeExtent validPeriod = proc.getValidTime();
                    if (validPeriod != null)
                    {
                        metadata.validTimeBegin = validPeriod.begin();
                        metadata.validTimeEnd = validPeriod.hasEnd() ? validPeriod.end() :
                            Instant.now().truncatedTo(ChronoUnit.SECONDS);
                    }
                    sensor.setMetadata(metadata);
                    sensor.setEncodingType(FORMAT_SML2);
                }
            }
        }

        // expand navigation links
        if (q != null && q.getExpand() != null)
        {
            for (Expand exp: q.getExpand())
            {
                NavigationProperty prop = exp.getPath().get(0);
                if (prop == NavigationProperty.DATASTREAMS)
                {
                    ResourcePath linkedPath = FrostUtils.getNavigationLinkPath(sensor.getId(), EntityType.SENSOR, EntityType.DATASTREAM);
                    EntitySet<?> linkedEntities = pm.dataStreamHandler.queryCollection(linkedPath, exp.getSubQuery());
                    sensor.setDatastreams((EntitySet<Datastream>)linkedEntities);
                }

                if (prop == NavigationProperty.MULTIDATASTREAMS)
                {
                    ResourcePath linkedPath = FrostUtils.getNavigationLinkPath(sensor.getId(), EntityType.SENSOR, EntityType.MULTIDATASTREAM);
                    EntitySet<?> linkedEntities = pm.dataStreamHandler.queryCollection(linkedPath, exp.getSubQuery());
                    sensor.setMultiDatastreams((EntitySet<MultiDatastream>)linkedEntities);
                }
            }
        }

        return sensor;
    }


    protected ResourceId handleSensorAssoc(Sensor sensor) throws NoSuchEntityException
    {
        Asserts.checkArgument(sensor != null, MISSING_ASSOC);
        ResourceId sensorId;

        if (sensor.getName() == null)
        {
            sensorId = (ResourceId)sensor.getId();
            Asserts.checkArgument(sensorId != null, MISSING_ASSOC);
            checkSensorIDInWriteStore(sensorId.asLong());
        }
        else
        {
            // deep insert
            sensorId = create(sensor);
        }

        return sensorId;
    }


    /*
     * Check that sensorID is present in database and exposed by service
     */
    protected void checkSensorID(long publicID) throws NoSuchEntityException
    {
        if (procReadStore.getCurrentVersionKey(publicID) == null)
            throw new NoSuchEntityException(NOT_FOUND_MESSAGE + publicID);
    }
    
    
    /*
     * Check that sensorID is present in writable database
     */
    protected void checkSensorIDInWriteStore(long publicID) throws NoSuchEntityException
    {
        long localID = pm.toLocalID(publicID);
        if (procWriteStore.getCurrentVersionKey(localID) == null)
            throw new NoSuchEntityException(NOT_FOUND_MESSAGE + publicID);
    }


    protected boolean isSensorVisible(long publicID)
    {
        return true;
    }


    protected void checkProcedureWritable(long publicID)
    {
        checkTransactionsEnabled();
        
        // TODO also check that current user has the right to write this procedure!

        if (!pm.isInWritableDatabase(publicID))
            throw new UnsupportedOperationException(NOT_WRITABLE_MESSAGE + publicID);
    }
    
    
    protected void checkTransactionsEnabled()
    {
        if (procWriteStore == null)
            throw new UnsupportedOperationException(NO_DB_MESSAGE);
    }

}