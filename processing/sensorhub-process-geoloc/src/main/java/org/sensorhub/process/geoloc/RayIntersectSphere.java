/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.process.geoloc;

import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.Quantity;
import net.opengis.swe.v20.Vector;
import org.sensorhub.algo.geoloc.EllipsoidIntersect;
import org.sensorhub.algo.vecmath.Vect3d;
import org.sensorhub.api.processing.OSHProcessInfo;
import org.vast.process.ExecutableProcessImpl;
import org.vast.process.ProcessException;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;


/**
 * <p>
 * Computes intersection of a 3D ray with a sphere which axes are
 * aligned with the axes of the referential of the ray. This process outputs
 * coordinates of the intersect point expressed in the same frame.
 * </p>

 * @author Alex Robin
 * @since Nov 13, 2015
 */
public class RayIntersectSphere extends ExecutableProcessImpl
{
    public static final OSHProcessInfo INFO = new OSHProcessInfo("RayIntersectSphere", "Ray Sphere Intersection", "Compute 3D intersection between a ray and a sphere", RayIntersectSphere.class);
    
    protected Vector rayOrigin;
    protected Vector rayDirection;
    protected Vector intersection;
    protected Quantity sphereRadius;
    
    protected EllipsoidIntersect rie;
    protected Vect3d origin;
    protected Vect3d dir;
    protected Vect3d intersect;
    protected double radius;
    

    public RayIntersectSphere()
    {
        super(INFO);
        GeoPosHelper sweHelper = new GeoPosHelper();
        
        //// INPUTS ////
        // ray origin in reference frame
        rayOrigin = sweHelper.newLocationVectorXYZ(null, SWEConstants.NIL_UNKNOWN, "m");
        inputData.add("rayOrigin", rayOrigin);
        
        // ray direction in reference frame
        rayDirection = sweHelper.newUnitVectorXYZ(null, SWEConstants.NIL_UNKNOWN);
        inputData.add("rayDirection", rayDirection);
        
        //// PARAMETERS ////
        // sphere radius
        sphereRadius = sweHelper.createQuantity()
            .definition(SWEHelper.getQudtUri("Radius"))
            .label("Sphere Radius")
            .description("Radius of sphere to interest with")
            .uom("m")
            .build();
        paramData.add("sphereRadius", sphereRadius);        
        
        //// OUTPUTS ////
        intersection = sweHelper.newLocationVectorECEF(null);
        outputData.add("intersection", intersection);
    }

    
    @Override
    public void init() throws ProcessException
    {
        super.init();
        
        this.origin = new Vect3d();
        this.dir = new Vect3d();
        this.intersect = new Vect3d();
        
        // instantiate ellipsoid intersection algorithm
        rie = new EllipsoidIntersect(radius, radius, radius);
    }
    
    
    @Override
    public void execute() throws ProcessException
    {
        // get ray origin input
        DataBlock originData = rayOrigin.getData();
        origin.x = originData.getDoubleValue(0);
        origin.y = originData.getDoubleValue(1);
        origin.z = originData.getDoubleValue(2);
        
        // get ray direction input
        DataBlock dirData = rayDirection.getData();
        dir.x = dirData.getDoubleValue(0);
        dir.y = dirData.getDoubleValue(1);
        dir.z = dirData.getDoubleValue(2);
        
        boolean ok = rie.computeIntersection(origin, dir, intersect);
        if (!ok)
            getLogger().debug("No intersection found");
        
        // set intersection point output
        DataBlock intersectData = intersection.getData();
        intersectData.setDoubleValue(0, intersect.x);
        intersectData.setDoubleValue(1, intersect.y);
        intersectData.setDoubleValue(2, intersect.z);
    }
}
