/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sweapi;

import org.sensorhub.api.module.IModule;
import org.sensorhub.api.module.IModuleProvider;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.impl.module.JarModuleProvider;


/**
 * <p>
 * Descriptor of SensorWeb API service module, needed for automatic discovery by
 * the ModuleRegistry.
 * </p>
 *
 * @author Alex Robin
 * @since Oct 12, 2020
 */
public class SWEApiWithMqttServiceDescriptor extends JarModuleProvider implements IModuleProvider
{

    @Override
    public String getModuleName()
    {
        return "SWE API Service with MQTT support";
    }


    @Override
    public String getModuleDescription()
    {
        return "Data access service compliant with the OGC SWE API standard";
    }


    @Override
    public Class<? extends IModule<?>> getModuleClass()
    {
        return SWEApiWithMqttService.class;
    }


    @Override
    public Class<? extends ModuleConfig> getModuleConfigClass()
    {
        return SWEApiWithMqttServiceConfig.class;
    }

}