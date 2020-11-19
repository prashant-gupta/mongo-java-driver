/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.internal.connection;

import com.mongodb.MongoCompressor;
import com.mongodb.MongoCredential;
import com.mongodb.MongoException;
import com.mongodb.MongoSecurityException;
import com.mongodb.ServerApi;
import com.mongodb.connection.ConnectionDescription;
import com.mongodb.connection.ConnectionId;
import com.mongodb.connection.ServerDescription;
import com.mongodb.connection.ServerType;
import com.mongodb.internal.async.SingleResultCallback;
import com.mongodb.lang.Nullable;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;

import java.util.List;

import static com.mongodb.assertions.Assertions.notNull;
import static com.mongodb.internal.connection.CommandHelper.executeCommand;
import static com.mongodb.internal.connection.CommandHelper.executeCommandAsync;
import static com.mongodb.internal.connection.CommandHelper.executeCommandWithoutCheckingForFailure;
import static com.mongodb.internal.connection.DefaultAuthenticator.USER_NOT_FOUND_CODE;
import static com.mongodb.internal.connection.DescriptionHelper.createConnectionDescription;
import static com.mongodb.internal.connection.DescriptionHelper.createServerDescription;
import static java.lang.String.format;

public class InternalStreamConnectionInitializer implements InternalConnectionInitializer {
    private final Authenticator authenticator;
    private final BsonDocument clientMetadataDocument;
    private final List<MongoCompressor> requestedCompressors;
    private final boolean checkSaslSupportedMechs;
    private final ServerApi serverApi;

    public InternalStreamConnectionInitializer(final Authenticator authenticator, final BsonDocument clientMetadataDocument,
                                               final List<MongoCompressor> requestedCompressors, final @Nullable ServerApi serverApi) {
        this.authenticator = authenticator;
        this.clientMetadataDocument = clientMetadataDocument;
        this.requestedCompressors = notNull("requestedCompressors", requestedCompressors);
        this.checkSaslSupportedMechs = authenticator instanceof DefaultAuthenticator;
        this.serverApi = serverApi;
    }

    @Override
    public InternalConnectionInitializationDescription initialize(final InternalConnection internalConnection) {
        notNull("internalConnection", internalConnection);

        InternalConnectionInitializationDescription description = initializeConnectionDescription(internalConnection);
        authenticate(internalConnection, description.getConnectionDescription());
        return completeConnectionDescriptionInitialization(internalConnection, description);
    }

    @Override
    public void initializeAsync(final InternalConnection internalConnection,
                                final SingleResultCallback<InternalConnectionInitializationDescription> callback) {
        initializeConnectionDescriptionAsync(internalConnection, createConnectionDescriptionCallback(internalConnection, callback));
    }

    private SingleResultCallback<InternalConnectionInitializationDescription>
    createConnectionDescriptionCallback(final InternalConnection internalConnection,
                                        final SingleResultCallback<InternalConnectionInitializationDescription> callback) {
        return new SingleResultCallback<InternalConnectionInitializationDescription>() {
            @Override
            public void onResult(final InternalConnectionInitializationDescription description, final Throwable t) {
                if (t != null) {
                    callback.onResult(null, t);
                } else if (authenticator == null || description.getConnectionDescription().getServerType()
                        == ServerType.REPLICA_SET_ARBITER) {
                    completeConnectionDescriptionInitializationAsync(internalConnection, description, callback);
                } else {
                    authenticator.authenticateAsync(internalConnection, description.getConnectionDescription(),
                            new SingleResultCallback<Void>() {
                        @Override
                        public void onResult(final Void result1, final Throwable t1) {
                            if (t1 != null) {
                                callback.onResult(null, t1);
                            } else {
                                completeConnectionDescriptionInitializationAsync(internalConnection, description, callback);
                            }
                        }
                    });
                }
            }
        };
    }

    private InternalConnectionInitializationDescription initializeConnectionDescription(final InternalConnection internalConnection) {
        BsonDocument isMasterResult;
        BsonDocument isMasterCommandDocument = createIsMasterCommand(authenticator, internalConnection);

        long start = System.nanoTime();
        try {
            isMasterResult = executeCommand("admin", isMasterCommandDocument, serverApi, internalConnection);
        } catch (MongoException e) {
            if (checkSaslSupportedMechs && e.getCode() == USER_NOT_FOUND_CODE) {
                MongoCredential credential = authenticator.getMongoCredential();
                throw new MongoSecurityException(credential, format("Exception authenticating %s", credential), e);
            }
            throw e;
        }
        long elapsedTime = System.nanoTime() - start;

        ConnectionDescription connectionDescription = createConnectionDescription(internalConnection.getDescription().getConnectionId(),
                isMasterResult);
        ServerDescription serverDescription = createServerDescription(internalConnection.getDescription().getServerAddress(),
                isMasterResult, elapsedTime);
        setSpeculativeAuthenticateResponse(isMasterResult);
        return new InternalConnectionInitializationDescription(connectionDescription, serverDescription);
    }

    private BsonDocument createIsMasterCommand(final Authenticator authenticator, final InternalConnection connection) {
        BsonDocument isMasterCommandDocument = new BsonDocument("ismaster", new BsonInt32(1));
        if (clientMetadataDocument != null) {
            isMasterCommandDocument.append("client", clientMetadataDocument);
        }
        if (!requestedCompressors.isEmpty()) {
            BsonArray compressors = new BsonArray();
            for (MongoCompressor cur : this.requestedCompressors) {
                compressors.add(new BsonString(cur.getName()));
            }
            isMasterCommandDocument.append("compression", compressors);
        }
        if (checkSaslSupportedMechs) {
            MongoCredential credential = authenticator.getMongoCredential();
            isMasterCommandDocument.append("saslSupportedMechs",
                    new BsonString(credential.getSource() + "." + credential.getUserName()));
        }
        if (authenticator instanceof SpeculativeAuthenticator) {
            BsonDocument speculativeAuthenticateDocument =
                    ((SpeculativeAuthenticator) authenticator).createSpeculativeAuthenticateCommand(connection);
            if (speculativeAuthenticateDocument != null) {
                isMasterCommandDocument.append("speculativeAuthenticate", speculativeAuthenticateDocument);
            }
        }
        return isMasterCommandDocument;
    }

    private InternalConnectionInitializationDescription completeConnectionDescriptionInitialization(
            final InternalConnection internalConnection,
            final InternalConnectionInitializationDescription description) {

        if (description.getConnectionDescription().getConnectionId().getServerValue() != null) {
            return description;
        }

        return applyGetLastErrorResult(executeCommandWithoutCheckingForFailure("admin",
                new BsonDocument("getlasterror", new BsonInt32(1)), serverApi,
                internalConnection),
                description);
    }

    private void authenticate(final InternalConnection internalConnection, final ConnectionDescription connectionDescription) {
        if (authenticator != null && connectionDescription.getServerType() != ServerType.REPLICA_SET_ARBITER) {
            authenticator.authenticate(internalConnection, connectionDescription);
        }
    }

    private void initializeConnectionDescriptionAsync(final InternalConnection internalConnection,
                                                      final SingleResultCallback<InternalConnectionInitializationDescription> callback) {
        final long startTime = System.nanoTime();
        executeCommandAsync("admin", createIsMasterCommand(authenticator, internalConnection), internalConnection,
                new SingleResultCallback<BsonDocument>() {
                    @Override
                    public void onResult(final BsonDocument isMasterResult, final Throwable t) {
                        if (t != null) {
                            if (checkSaslSupportedMechs && t instanceof MongoException
                                    && ((MongoException) t).getCode() == USER_NOT_FOUND_CODE) {
                                MongoCredential credential = authenticator.getMongoCredential();
                                callback.onResult(null, new MongoSecurityException(credential,
                                        format("Exception authenticating %s", credential), t));
                            } else {
                                callback.onResult(null, t);
                            }
                        } else {
                            ConnectionId connectionId = internalConnection.getDescription().getConnectionId();
                            ConnectionDescription connectionDescription = createConnectionDescription(connectionId, isMasterResult);
                            ServerDescription serverDescription =
                                    createServerDescription(internalConnection.getDescription().getServerAddress(), isMasterResult,
                                            System.nanoTime() - startTime);
                            setSpeculativeAuthenticateResponse(isMasterResult);
                            callback.onResult(new InternalConnectionInitializationDescription(connectionDescription, serverDescription),
                                    null);
                        }
                    }
                });
    }

    private void setSpeculativeAuthenticateResponse(final BsonDocument isMasterResult) {
        if (authenticator instanceof SpeculativeAuthenticator) {
            ((SpeculativeAuthenticator) authenticator).setSpeculativeAuthenticateResponse(
                    isMasterResult.getDocument("speculativeAuthenticate", null));
        }
    }

    private void completeConnectionDescriptionInitializationAsync(
            final InternalConnection internalConnection,
            final InternalConnectionInitializationDescription description,
            final SingleResultCallback<InternalConnectionInitializationDescription> callback) {

        if (description.getConnectionDescription().getConnectionId().getServerValue() != null) {
            callback.onResult(description, null);
            return;
        }

        executeCommandAsync("admin", new BsonDocument("getlasterror", new BsonInt32(1)),
                internalConnection,
                new SingleResultCallback<BsonDocument>() {
                    @Override
                    public void onResult(final BsonDocument result, final Throwable t) {
                        if (t != null) {
                            callback.onResult(description, null);
                        } else {
                            callback.onResult(applyGetLastErrorResult(result, description), null);
                        }
                    }
                });
    }

    private InternalConnectionInitializationDescription applyGetLastErrorResult(
            final BsonDocument getLastErrorResult,
            final InternalConnectionInitializationDescription description) {

        ConnectionDescription connectionDescription = description.getConnectionDescription();
        ConnectionId connectionId;

        if (getLastErrorResult.containsKey("connectionId")) {
            connectionId = connectionDescription.getConnectionId()
                    .withServerValue(getLastErrorResult.getNumber("connectionId").intValue());
        } else {
            connectionId = connectionDescription.getConnectionId();
        }

        return description.withConnectionDescription(connectionDescription.withConnectionId(connectionId));
    }
}
