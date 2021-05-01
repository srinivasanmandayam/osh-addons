/***************************** BEGIN LICENSE BLOCK ***************************

 The contents of this file are subject to the Mozilla Public License, v. 2.0.
 If a copy of the MPL was not distributed with this file, You can obtain one
 at http://mozilla.org/MPL/2.0/.

 Software distributed under the License is distributed on an "AS IS" basis,
 WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 for the specific language governing rights and limitations under the License.

 Copyright (C) 2021 Botts Innovative Research, Inc. All Rights Reserved.

 ******************************* END LICENSE BLOCK ***************************/
package org.sensorhub.impl.sensor.uas.outputs;

import org.sensorhub.impl.sensor.uas.UasSensor;
import org.sensorhub.impl.sensor.uas.config.UasConfig;
import net.opengis.swe.v20.DataBlock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.api.event.IEventListener;
import org.sensorhub.api.data.IStreamingDataInterface;
import org.sensorhub.api.data.DataEvent;

import java.io.File;
import java.net.URL;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class IdentificationTest {

    private UasSensor driver = null;

    private final Object syncObject = new Object();

    @Before
    public void init() throws Exception {

        URL resource = getClass().getClassLoader().getResource("sample-stream.ts");
        UasConfig config = new UasConfig();

        assert resource != null;
        config.connection.transportStreamPath = new File(resource.toURI()).getPath();
        config.outputs.enableIdentification = true;

        driver = new UasSensor();
        driver.setConfiguration(config);
        driver.init();
    }

    @After
    public void cleanup() throws Exception {

        driver.stop();
    }

    @Test
    public void testIdentification() throws Exception {

        driver.start();

        // register listener on data interface
        IStreamingDataInterface di = driver.getObservationOutputs().values().iterator().next();

        IEventListener listener = event -> {

            assertTrue(event instanceof DataEvent);
            DataEvent newDataEvent = (DataEvent) event;

            DataBlock dataBlock = newDataEvent.getRecords()[0];

            assertTrue(dataBlock.getDoubleValue(0) > 0);
            assertNotNull(dataBlock.getStringValue(1));
            assertNotNull(dataBlock.getStringValue(2));

            synchronized (syncObject) {

                syncObject.notify();
            }
        };

        di.registerListener(listener);

        // start capture and wait until we receive the first frame
        synchronized (syncObject) {

            syncObject.wait();
        }

        di.unregisterListener(listener);
    }
}
