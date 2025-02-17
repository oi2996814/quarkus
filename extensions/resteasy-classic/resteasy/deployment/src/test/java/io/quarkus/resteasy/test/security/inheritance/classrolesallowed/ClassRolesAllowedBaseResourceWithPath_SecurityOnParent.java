package io.quarkus.resteasy.test.security.inheritance.classrolesallowed;

import static io.quarkus.resteasy.test.security.inheritance.SubPaths.CLASS_PATH_ON_RESOURCE;
import static io.quarkus.resteasy.test.security.inheritance.SubPaths.CLASS_ROLES_ALLOWED_PREFIX;
import static io.quarkus.resteasy.test.security.inheritance.SubPaths.CLASS_SECURITY_ON_PARENT;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Path(CLASS_ROLES_ALLOWED_PREFIX + CLASS_SECURITY_ON_PARENT + CLASS_PATH_ON_RESOURCE)
public class ClassRolesAllowedBaseResourceWithPath_SecurityOnParent
        extends ClassRolesAllowedParentResourceWithoutPath_SecurityOnParent {

}
