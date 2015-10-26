/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.usergrid.rest.management.organizations;


import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import org.apache.amber.oauth2.common.exception.OAuthSystemException;
import org.apache.commons.lang.NullArgumentException;

import org.apache.usergrid.management.ActivationState;
import org.apache.usergrid.management.OrganizationConfig;
import org.apache.usergrid.management.OrganizationInfo;
import org.apache.usergrid.management.export.ExportFilter;
import org.apache.usergrid.management.export.ExportFilterImpl;
import org.apache.usergrid.management.export.ExportService;
import org.apache.usergrid.persistence.Query;
import org.apache.usergrid.persistence.entities.Export;
import org.apache.usergrid.persistence.queue.impl.UsergridAwsCredentials;
import org.apache.usergrid.rest.AbstractContextResource;
import org.apache.usergrid.rest.ApiResponse;
import org.apache.usergrid.rest.applications.ServiceResource;
import org.apache.usergrid.rest.exceptions.RedirectionException;
import org.apache.usergrid.rest.management.organizations.applications.ApplicationsResource;
import org.apache.usergrid.rest.management.organizations.users.UsersResource;
import org.apache.usergrid.rest.security.annotations.RequireOrganizationAccess;
import org.apache.usergrid.rest.security.annotations.RequireSystemAccess;
import org.apache.usergrid.rest.utils.JSONPUtils;
import org.apache.usergrid.security.oauth.ClientCredentialsInfo;
import org.apache.usergrid.security.tokens.exceptions.TokenException;
import org.apache.usergrid.services.ServiceResults;

import com.sun.jersey.api.json.JSONWithPadding;
import com.sun.jersey.api.view.Viewable;

import static javax.servlet.http.HttpServletResponse.SC_ACCEPTED;
import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;


@Component("org.apache.usergrid.rest.management.organizations.OrganizationResource")
@Scope("prototype")
@Produces({
        MediaType.APPLICATION_JSON, "application/javascript", "application/x-javascript", "text/ecmascript",
        "application/ecmascript", "text/jscript"
})
public class OrganizationResource extends AbstractContextResource {

    private static final Logger logger = LoggerFactory.getLogger( OrganizationsResource.class );

    @Autowired
    protected ExportService exportService;

    OrganizationInfo organization;


    public OrganizationResource() {
        logger.debug("OrganizationResource created");
    }


    public OrganizationResource init( OrganizationInfo organization ) {
        this.organization = organization;
        logger.debug("OrganizationResource initialized for org {}", organization.getName());
        return this;
    }


    @RequireOrganizationAccess
    @Path("users")
    public UsersResource getOrganizationUsers( @Context UriInfo ui ) throws Exception {
        return getSubResource( UsersResource.class ).init( organization );
    }


    @RequireOrganizationAccess
    @Path("applications")
    public ApplicationsResource getOrganizationApplications( @Context UriInfo ui ) throws Exception {
        return getSubResource( ApplicationsResource.class ).init( organization );
    }


    @RequireOrganizationAccess
    @Path("apps")
    public ApplicationsResource getOrganizationApplications2( @Context UriInfo ui ) throws Exception {
        return getSubResource( ApplicationsResource.class ).init( organization );
    }


    @GET
    public JSONWithPadding getOrganizationDetails( @Context UriInfo ui,
                                                   @QueryParam("callback") @DefaultValue("callback") String callback )
            throws Exception {

        logger.info( "Get details for organization: " + organization.getUuid() );

        ApiResponse response = createApiResponse();
        response.setProperty( "organization", management.getOrganizationData( organization ) );

        return new JSONWithPadding( response, callback );
    }


    @GET
    @Path("activate")
    @Produces(MediaType.TEXT_HTML)
    public Viewable activate( @Context UriInfo ui, @QueryParam("token") String token ) {

        try {
            management.handleActivationTokenForOrganization( organization.getUuid(), token );
            return handleViewable( "activate", this );
        }
        catch ( TokenException e ) {
            return handleViewable( "bad_activation_token", this );
        }
        catch ( RedirectionException e ) {
            throw e;
        }
        catch ( Exception e ) {
            return handleViewable( "error", e );
        }
    }


    @GET
    @Path("confirm")
    @Produces(MediaType.TEXT_HTML)
    public Viewable confirm( @Context UriInfo ui, @QueryParam("token") String token ) {

        try {
            ActivationState state = management.handleActivationTokenForOrganization( organization.getUuid(), token );
            if ( state == ActivationState.CONFIRMED_AWAITING_ACTIVATION ) {
                return handleViewable( "confirm", this );
            }
            return handleViewable( "activate", this );
        }
        catch ( TokenException e ) {
            return handleViewable( "bad_activation_token", this );
        }
        catch ( RedirectionException e ) {
            throw e;
        }
        catch ( Exception e ) {
            return handleViewable( "error", e );
        }
    }


    @GET
    @Path("reactivate")
    public JSONWithPadding reactivate( @Context UriInfo ui,
                                       @QueryParam("callback") @DefaultValue("callback") String callback )
            throws Exception {

        logger.info( "Send activation email for organization: " + organization.getUuid() );

        ApiResponse response = createApiResponse();

        management.startOrganizationActivationFlow( organization );

        response.setAction( "reactivate organization" );
        return new JSONWithPadding( response, callback );
    }


    @RequireOrganizationAccess
    @GET
    @Path("feed")
    public JSONWithPadding getFeed( @Context UriInfo ui,
                                    @QueryParam("callback") @DefaultValue("callback") String callback )
            throws Exception {

        ApiResponse response = createApiResponse();
        response.setAction( "get organization feed" );

        ServiceResults results = management.getOrganizationActivity( organization );
        response.setEntities( results.getEntities() );
        response.setSuccess();

        return new JSONWithPadding( response, callback );
    }


    @RequireOrganizationAccess
    @GET
    @Path("credentials")
    public JSONWithPadding getCredentials( @Context UriInfo ui,
                                           @QueryParam("callback") @DefaultValue("callback") String callback )
            throws Exception {

        ApiResponse response = createApiResponse();
        response.setAction( "get organization client credentials" );

        ClientCredentialsInfo keys =
                new ClientCredentialsInfo( management.getClientIdForOrganization( organization.getUuid() ),
                        management.getClientSecretForOrganization( organization.getUuid() ) );

        response.setCredentials( keys );
        return new JSONWithPadding( response, callback );
    }


    @RequireOrganizationAccess
    @POST
    @Path("credentials")
    public JSONWithPadding generateCredentials( @Context UriInfo ui,
                                                @QueryParam("callback") @DefaultValue("callback") String callback )
            throws Exception {

        ApiResponse response = createApiResponse();
        response.setAction( "generate organization client credentials" );

        ClientCredentialsInfo credentials =
                new ClientCredentialsInfo( management.getClientIdForOrganization( organization.getUuid() ),
                        management.newClientSecretForOrganization( organization.getUuid() ) );

        response.setCredentials( credentials );
        return new JSONWithPadding( response, callback );
    }


    public OrganizationInfo getOrganization() {
        return organization;
    }


    @RequireOrganizationAccess
    @Consumes(MediaType.APPLICATION_JSON)
    @PUT
    public JSONWithPadding executePut( @Context UriInfo ui, Map<String, Object> json,
                                       @QueryParam("callback") @DefaultValue("callback") String callback )
            throws Exception {

        logger.debug( "executePut" );

        ApiResponse response = createApiResponse();
        response.setAction( "put" );

        response.setParams( ui.getQueryParameters() );

        Map customProperties = ( Map ) json.get( OrganizationsResource.ORGANIZATION_PROPERTIES );
        organization.setProperties( customProperties );
        management.updateOrganization( organization );

        return new JSONWithPadding( response, callback );
    }

    @POST
    @Path("export")
    @Consumes(APPLICATION_JSON)
    @RequireOrganizationAccess
    public Response exportPostJson( @Context UriInfo ui,Map<String, Object> json,
                                    @QueryParam("callback") @DefaultValue("") String callback )
            throws OAuthSystemException {

        logger.debug( "executePostJson" );

        UsergridAwsCredentials uac = new UsergridAwsCredentials();

        UUID jobUUID = null;
        Map<String, String> uuidRet = new HashMap<String, String>();

        Map<String,Object> properties;
        Map<String, Object> storage_info;

        //the storage providers could be an abstract class that others can implement in order to try to pull out
        //their own data.
        try {
            if((properties = ( Map<String, Object> )  json.get( "properties" )) == null){
                throw new NullArgumentException("Could not find 'properties'");
            }
            storage_info = ( Map<String, Object> ) properties.get( "storage_info" );
            String storage_provider = ( String ) properties.get( "storage_provider" );
            if(storage_provider == null) {
                throw new NullArgumentException( "Could not find field 'storage_provider'" );
            }
            if(storage_info == null) {
                throw new NullArgumentException( "Could not find field 'storage_info'" );
            }

            String bucketName = ( String ) storage_info.get( "bucket_location" );
            String accessId = ( String ) storage_info.get( "s3_access_id" );
            String secretKey = ( String ) storage_info.get( "s3_key" );

            if ( bucketName == null ) {
                throw new NullArgumentException( "Could not find field 'bucketName'" );
            }
            if ( accessId == null ) {
                throw new NullArgumentException( "Could not find field 's3_access_id'" );
            }
            if ( secretKey == null ) {

                throw new NullArgumentException( "Could not find field 's3_key'" );
            }




            //organizationid is added after the fact so that
            json.put( "organizationId",organization.getUuid());
            ExportFilter exportFilter = exportFilterParser( json );
            jobUUID = exportService.schedule( json,exportFilter );

            uuidRet.put( "Export Entity", jobUUID.toString() );
        }
        catch ( NullArgumentException e ) {
            return Response.status( SC_BAD_REQUEST ).type( JSONPUtils.jsonMediaType( callback ) )
                           .entity( ServiceResource.wrapWithCallback( e.getMessage(), callback ) ).build();
        }
        catch ( Exception e ) {
            //TODO:throw descriptive error message and or include on in the response
            //TODO:fix below, it doesn't work if there is an exception. Make it look like the OauthResponse.
            return Response.status(  SC_INTERNAL_SERVER_ERROR ).type( JSONPUtils.jsonMediaType( callback ) )
                           .entity( ServiceResource.wrapWithCallback( e.getMessage(), callback ) ).build();
        }
        return Response.status( SC_ACCEPTED ).entity( uuidRet ).build();
    }

    //need to explore a validate query method.
    public ExportFilter exportFilterParser(Map<String,Object> input){
        String query = ( String ) input.get( "ql" );
        Set applicationSet = ( Set ) input.get( "apps" );
        Set collectionSet = ( Set ) input.get( "collections" );
        Set connectionSet = ( Set ) input.get( "connections" );

        //TODO:GREY move export filter to the top of this .
        ExportFilter exportFilter = new ExportFilterImpl();
        exportFilter.setApplications( applicationSet );
        exportFilter.setCollections( collectionSet );
        exportFilter.setConnections( connectionSet );
        //this references core, there needs to be a better exposed way to do this
        //as well as a way to verify queries.
        exportFilter.setQuery( Query.fromQL( query ));

        return exportFilter;
    }

    @GET
    @RequireOrganizationAccess
    @Path("export/{exportEntity: [A-Fa-f0-9]{8}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}-[A-Fa-f0-9]{12}}")
    public Response exportGetJson( @Context UriInfo ui, @PathParam("exportEntity") UUID exportEntityUUIDStr,
                                   @QueryParam("callback") @DefaultValue("") String callback ) throws Exception {

        Export entity;
        try {
            entity = smf.getServiceManager( emf.getManagementAppId() ).getEntityManager()
                        .get( exportEntityUUIDStr, Export.class );
        }
        catch ( Exception e ) { //this might not be a bad request and needs better error checking
            return Response.status( SC_BAD_REQUEST ).type( JSONPUtils.jsonMediaType( callback ) )
                           .entity( ServiceResource.wrapWithCallback( e.getMessage(), callback ) ).build();
        }

        if ( entity == null ) {
            return Response.status( SC_BAD_REQUEST ).build();
        }

        return Response.status( SC_OK ).entity( entity).build();
    }


    @RequireSystemAccess
    @GET
    @Path("config")
    public JSONWithPadding getConfig( @Context UriInfo ui,
                                      @QueryParam("callback") @DefaultValue("callback") String callback )
            throws Exception {

        logger.info( "Get configuration for organization: " + organization.getUuid() );

        // TODO: check for super user, @RequireSystemAccess didn't work

        ApiResponse response = createApiResponse();
        response.setAction( "get organization configuration" );

        // TODO: check for super user

        OrganizationConfig orgConfig =
                management.getOrganizationConfigByUuid( organization.getUuid() );

        response.setProperty( "configuration", management.getOrganizationConfigData( orgConfig ) );
        // response.setOrganizationConfig( orgConfig );

        return new JSONWithPadding( response, callback );
    }


    @RequireSystemAccess
    @Consumes(MediaType.APPLICATION_JSON)
    @PUT
    @Path("config")
    public JSONWithPadding putConfig( @Context UriInfo ui, Map<String, Object> json,
                                       @QueryParam("callback") @DefaultValue("callback") String callback )
            throws Exception {

        logger.debug("Put configuration for organization: " + organization.getUuid());

        ApiResponse response = createApiResponse();
        response.setAction("put organization configuration");

        // TODO: check for super user

        // response.setParams(ui.getQueryParameters());

        OrganizationConfig orgConfig =
                management.getOrganizationConfigByUuid( organization.getUuid() );
        orgConfig.addProperties(json);
        management.updateOrganizationConfig(orgConfig);

        // refresh orgConfig -- to pick up removed entries and defaults
        orgConfig = management.getOrganizationConfigByUuid( organization.getUuid() );
        response.setProperty( "configuration", management.getOrganizationConfigData( orgConfig ) );

        return new JSONWithPadding( response, callback );
    }

}
