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

import org.sensorhub.api.feature.FeatureFilter;


/**
 * <p>
 * Immutable filter object for SensorThings Thing entities.<br/>
 * There is an implicit AND between all filter parameters.
 * </p>
 *
 * @author Alex Robin
 * @date Oct 29, 2019
 */
class STAThingFilter extends FeatureFilter
{
    protected FeatureFilter locations;
    

    public FeatureFilter getLocations()
    {
        return locations;
    }
    
    
    public static class Builder extends FeatureFilterBuilder<Builder, STAThingFilter>
    {
        protected Builder()
        {
            super(new STAThingFilter());
        }
        
        
        public Builder withLocations(FeatureFilter filter)
        {
            instance.locations = filter;
            return this;
        }


        public Builder withLocations(Long... locationIDs)
        {
            instance.locations = new FeatureFilter.Builder()
                .withInternalIDs(locationIDs)
                .build();
            return this;
        }
    }        
}