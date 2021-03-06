/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.modules.objectstorage;

import java.io.FileNotFoundException;

import javax.inject.Singleton;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.blob.api.BucketName;
import org.apache.james.blob.objectstorage.aws.AwsS3AuthConfiguration;
import org.apache.james.blob.objectstorage.aws.Region;
import org.apache.james.blob.objectstorage.aws.S3BlobStoreConfiguration;
import org.apache.james.blob.objectstorage.aws.S3BlobStoreDAO;
import org.apache.james.modules.mailbox.ConfigurationComponent;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.multibindings.ProvidesIntoSet;

public class S3BlobStoreModule extends AbstractModule {

    @Provides
    @Singleton
    private S3BlobStoreConfiguration getObjectStorageConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException {
        try {
            Configuration configuration = propertiesProvider.getConfigurations(ConfigurationComponent.NAMES);
            return S3BlobStoreConfigurationReader.from(configuration);
        } catch (FileNotFoundException e) {
            throw new ConfigurationException(ConfigurationComponent.NAME + " configuration was not found");
        }
    }

    @Provides
    @Singleton
    private AwsS3AuthConfiguration awsS3AuthConfiguration(S3BlobStoreConfiguration s3BlobStoreConfiguration) {
        return s3BlobStoreConfiguration.getSpecificAuthConfiguration();
    }

    @Provides
    @Singleton
    private BucketName defaultBucket(S3BlobStoreConfiguration s3BlobStoreConfiguration) {
        return s3BlobStoreConfiguration.getNamespace().orElse(BucketName.DEFAULT);
    }

    @Provides
    @Singleton
    private Region region(S3BlobStoreConfiguration s3BlobStoreConfiguration) {
        return s3BlobStoreConfiguration.getRegion();
    }

    @ProvidesIntoSet
    InitializationOperation startS3BlobStoreDAO(S3BlobStoreDAO s3BlobStoreDAO) {
        return InitilizationOperationBuilder
            .forClass(S3BlobStoreDAO.class)
            .init(s3BlobStoreDAO::start);
    }
}
