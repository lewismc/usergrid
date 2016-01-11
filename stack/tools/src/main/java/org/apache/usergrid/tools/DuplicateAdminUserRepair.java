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
package org.apache.usergrid.tools;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.StringUtils;
import org.apache.usergrid.management.ManagementService;
import org.apache.usergrid.persistence.EntityManagerFactory;
import rx.functions.Action1;

import java.util.*;

import static org.apache.usergrid.tools.UserOrgInterface.Org;
import static org.apache.usergrid.tools.UserOrgInterface.OrgUser;


/**
 * Find duplicate admin users, delete the one that is not indexed.
 */
public class DuplicateAdminUserRepair extends ToolBase {

    UserOrgInterface           manager = null;
    
    static final String        THREADS_ARG_NAME = "threads"; 
    
    int                        threadCount = 5;

    static final String        DRYRUN_ARG_NAME = "dryrun";

    boolean                    dryRun = false;

    Multimap<String, OrgUser>  emails = HashMultimap.create();
    
    Multimap<String, OrgUser>  usernames = HashMultimap.create();

    boolean                    testing = false;


    DuplicateAdminUserRepair() {
        super();
    }

    DuplicateAdminUserRepair(EntityManagerFactory emf, ManagementService managementService ) {
        this();
        this.emf = emf;
        this.managementService = managementService;
    }
    
    
    @Override
    @SuppressWarnings("static-access")
    public Options createOptions() {

        Options options = super.createOptions();

        Option dryRunOption = OptionBuilder.hasArg()
            .withType(Boolean.TRUE)
            .withDescription( "-" + DRYRUN_ARG_NAME + " true to print what tool would do and do not alter data.")
            .create( DRYRUN_ARG_NAME );
        options.addOption( dryRunOption );
        
        Option writeThreadsOption = OptionBuilder.hasArg()
            .withType(0)
            .withDescription( "Write Threads -" + THREADS_ARG_NAME )
            .create(THREADS_ARG_NAME);
        options.addOption( writeThreadsOption );        
        
        return options;
    }

    
    public UserOrgManager createNewRepairManager() {
        return new UserOrgManager( emf, managementService );
    }
   
    
    @Override
    public void runTool(CommandLine line) throws Exception {

        startSpring();
        setVerbose( line );

        if (StringUtils.isNotEmpty( line.getOptionValue( THREADS_ARG_NAME ) )) {
            try {
                threadCount = Integer.parseInt( line.getOptionValue( THREADS_ARG_NAME ) );
            } catch (NumberFormatException nfe) {
                logger.error( "-" + THREADS_ARG_NAME + " must be specified as an integer. Aborting..." );
                return;
            }
        }

        if ( StringUtils.isNotEmpty( line.getOptionValue( DRYRUN_ARG_NAME ) )) {
            dryRun = Boolean.parseBoolean( line.getOptionValue( DRYRUN_ARG_NAME ));
        }

        if ( manager == null ) { // we use a special manager when mockTesting
            if (dryRun) {
                manager = new DryRunUserOrgManager( emf, managementService );
            } else {
                manager = new UserOrgManager( emf, managementService );
            }
        } 

        logger.info( "DuplicateAdminUserRepair tool starting up... manager: " + manager.getClass().getSimpleName() );
       
        // build multi-map of users by email and users by name
        
        manager.getUsers().doOnNext( new Action1<OrgUser>() {
            @Override
            public void call( OrgUser user ) {

                if (user.getUsername() == null) {
                    logger.warn( "User {} has no username", user.getId() );
                    return;
                }
                if (user.getEmail() == null) {
                    logger.warn( "User {} has no email", user.getId() );
                    return;
                }
                emails.put( user.getEmail(), user );
                usernames.put( user.getEmail(), user );
                
            }
        } ).toBlocking().lastOrDefault( null );

        for ( String username : usernames.keySet() ) {
            Collection<OrgUser> users = usernames.get( username );

            if ( users.size() > 1 ) {
                logger.info( "Found multiple users with the username {}", username );

                // force the username to be reset to the user's email
                resolveUsernameConflicts( username, users );
            }
        }

        for ( String email : emails.keySet() ) {
            
            Collection<OrgUser> users = emails.get( email );

            if ( users.size() > 1 ) {
                // get the admin the same way as the rest tier, this way the OTHER
                // admins will be removed
                OrgUser targetUser = manager.lookupOrgUserByEmail( email );

                if ( targetUser == null ) {

                    List<OrgUser> tempUsers = new ArrayList<OrgUser>( users );
                    Collections.sort( tempUsers );

                    OrgUser toLoad = tempUsers.get( 0 );

                    logger.warn( "Could not load target user by email {}, loading by UUID {} instead", email, toLoad );
                    targetUser = toLoad;

                    users.remove( toLoad );
                }

                users.remove( targetUser );

                logger.warn( "Found multiple admins with the email {}.  Retaining uuid {}", email, targetUser.getId() );

                for ( OrgUser orgUser : users ) {
                    mergeAdmins( orgUser, targetUser );
                }

                // force the index update after all other admins have been merged
                if ( dryRun ) {
                    logger.info("Would force re-index of 'keeper' user {}:{}", 
                            targetUser.getUsername(), targetUser.getId());
                } else {
                    logger.info( "Forcing re-index of admin with email {} and id {}", email, targetUser.getId());
                    manager.updateOrgUser( targetUser );
                }
            }
        }

        logger.info( "Repair complete" );
    }


    /**
     * When our usernames are equal, we need to check if our emails are equal. If they're not, we need to change the one
     * that DOES NOT get returned on a lookup by username
     */
    private void resolveUsernameConflicts( String userName, Collection<OrgUser> users ) throws Exception {
        
        // lookup the admin id
        OrgUser existing = manager.lookupOrgUserByUsername( userName );

        if ( existing == null ) {
            logger.warn( "Could not determine an admin for colliding username '{}'.  Skipping", userName );
            return;
        }

        users.remove( existing );

        boolean collision = false;

        for ( OrgUser other : users ) {

            // same username and email, these will be merged later in the process,
            // skip it
            if ( other != null && other.getEmail() != null && other.getEmail().equals( existing.getEmail() ) ) {
                logger.info(
                        "Users with the same username '{}' have the same email '{}'. This will be resolved later in "
                                + "the process, skipping", userName, existing.getEmail() );
                continue;
            }

            // if we get here, the emails do not match, but the usernames do. Force
            // both usernames to emails
            collision = true;

            setUserName(other, other.getEmail() );
        }

        if ( collision ) {
            setUserName(existing, existing.getEmail() );
        }
    }


    /** Set the username to the one provided, if we can't due to duplicate property issues, we fall back to user+uuid */
    private void setUserName( OrgUser other, String newUserName ) throws Exception {

        if ( dryRun ) {
            logger.info("Would rename user {}:{} to {}", new Object[] { 
                    other.getUsername(), other.getId(), newUserName });
        } else {
            logger.info( "Setting username to {} for user with username {} and id {}", new Object[] {
                    newUserName, other.getUsername(), other.getId() } );
            
            manager.setOrgUserName( other, newUserName );
        }
    }


    /** Merge the source admin to the target admin by copying oranizations. Then deletes the source admin */
    private void mergeAdmins( OrgUser sourceUser, OrgUser targetUser ) throws Exception {

        Set<Org> sourceOrgs = manager.getUsersOrgs( sourceUser ); 

        for ( Org org : sourceOrgs ) {

            if ( dryRun ) {
                logger.info("Would add org {}:{} to user {}:{}", new Object[] {
                        org.getName(), org.getId(), targetUser.getUsername(), targetUser.getId(), });

            } else {
                logger.info( "Adding organization {}:{} to admin with email {} and id {}",
                    new Object[] { org.getName(), org.getId(), targetUser.getEmail(), targetUser.getId() } );

                // copy it over to the target admin
                manager.addUserToOrg( targetUser, org );
            }
        }

        logger.info( "Deleting admin with email {} and id {}", sourceUser.getEmail(), sourceUser.getId() );

        if ( dryRun ) {
            logger.info( "Would remove user {}:{}", new Object[]{
                    sourceUser.getUsername(), sourceUser.getId() } );
            
        } else {
            manager.removeOrgUser( sourceUser );
        }
    }

}