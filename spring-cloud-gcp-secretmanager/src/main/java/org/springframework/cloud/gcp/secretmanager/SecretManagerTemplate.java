/*
 * Copyright 2017-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gcp.secretmanager;

import java.util.stream.StreamSupport;

import com.google.cloud.secretmanager.v1beta1.AccessSecretVersionResponse;
import com.google.cloud.secretmanager.v1beta1.AddSecretVersionRequest;
import com.google.cloud.secretmanager.v1beta1.CreateSecretRequest;
import com.google.cloud.secretmanager.v1beta1.ProjectName;
import com.google.cloud.secretmanager.v1beta1.Replication;
import com.google.cloud.secretmanager.v1beta1.Secret;
import com.google.cloud.secretmanager.v1beta1.SecretManagerServiceClient;
import com.google.cloud.secretmanager.v1beta1.SecretManagerServiceClient.ListSecretsPagedResponse;
import com.google.cloud.secretmanager.v1beta1.SecretName;
import com.google.cloud.secretmanager.v1beta1.SecretPayload;
import com.google.cloud.secretmanager.v1beta1.SecretVersionName;
import com.google.protobuf.ByteString;

import org.springframework.cloud.gcp.core.GcpProjectIdProvider;

/**
 * Offers convenience methods for performing common operations on Secret Manager including
 * creating and reading secrets.
 *
 * @author Daniel Zou
 * @since 1.3
 */
public class SecretManagerTemplate {

	private final SecretManagerServiceClient secretManagerServiceClient;

	private final GcpProjectIdProvider projectIdProvider;

	public SecretManagerTemplate(
			SecretManagerServiceClient secretManagerServiceClient,
			GcpProjectIdProvider projectIdProvider) {
		this.secretManagerServiceClient = secretManagerServiceClient;
		this.projectIdProvider = projectIdProvider;
	}

	/**
	 * Creates a new secret using the provided {@code secretId} and creates a new secret
	 * version with the provided {@code payload}.
	 *
	 * <p>
	 * If there is already a secret saved in SecretManager with the specified
	 * {@code secretId}, then it simply creates a new version of the payload for the secret
	 * under that {@code secretId}.
	 *
	 * @param secretId the secret ID of the secret to create.
	 * @param payload the secret payload; supported payload types: (UTF-8 encoded) String and
	 *     byte[].
	 */
	public void createSecret(String secretId, Object payload) {
		if (!secretExists(secretId)) {
			createSecret(secretId);
		}

		createNewSecretVersion(secretId, payload);
	}

	/**
	 * Gets the secret payload of the specified {@code secretId} at version
	 * {@code versionName}.
	 *
	 * @param secretId unique identifier of your secret in Secret Manager.
	 * @param versionName which version of the secret to load. The version can be a version
	 *     number as a string (e.g. "5") or an alias (e.g. "latest").
	 * @return The secret payload as String
	 */
	public String getSecretString(String secretId, String versionName) {
		return getSecretVersion(secretId, versionName).toStringUtf8();
	}

	/**
	 * Gets the secret payload of the specified {@code secretId} at version
	 * {@code versionName}.
	 *
	 * @param secretId unique identifier of your secret in Secret Manager.
	 * @param versionName which version of the secret to load. The version can be a version
	 *     number as a string (e.g. "5") or an alias (e.g. "latest").
	 * @return The secret payload as byte[]
	 */
	public byte[] getSecretPayload(String secretId, String versionName) {
		return getSecretVersion(secretId, versionName).toByteArray();
	}

	private ByteString getSecretVersion(String secretId, String versionName) {
		SecretVersionName secretVersionName = SecretVersionName.of(
				this.projectIdProvider.getProjectId(),
				secretId,
				versionName);

		AccessSecretVersionResponse response = secretManagerServiceClient.accessSecretVersion(secretVersionName);

		return response.getPayload().getData();
	}

	/**
	 * Returns the lower-level {@link SecretManagerServiceClient} client object for making API
	 * calls to Secret Manager service.
	 *
	 * <p>
	 * Useful for executing more advanced use-cases that are not covered by
	 * {@link SecretManagerTemplate}.
	 *
	 * @return the {@link SecretManagerServiceClient} client object.
	 */
	public SecretManagerServiceClient getSecretManagerServiceClient() {
		return this.secretManagerServiceClient;
	}

	/**
	 * Create a new version of the secret with the specified payload under a {@link Secret}.
	 */
	private void createNewSecretVersion(String secretId, Object rawPayload) {
		ByteString payload = convertToByteString(rawPayload);

		SecretName name = SecretName.of(projectIdProvider.getProjectId(), secretId);
		SecretPayload payloadObject = SecretPayload.newBuilder()
				.setData(payload)
				.build();

		AddSecretVersionRequest payloadRequest = AddSecretVersionRequest.newBuilder()
				.setParent(name.toString())
				.setPayload(payloadObject)
				.build();
		secretManagerServiceClient.addSecretVersion(payloadRequest);
	}

	/**
	 * Creates a new secret for the GCP Project.
	 *
	 * <p>
	 * Note that the {@link Secret} object does not contain the secret payload. You must
	 * create versions of the secret which stores the payload of the secret.
	 */
	private void createSecret(String secretId) {
		ProjectName projectName = ProjectName.of(projectIdProvider.getProjectId());

		Secret secret = Secret.newBuilder()
				.setReplication(
						Replication.newBuilder()
								.setAutomatic(Replication.Automatic.newBuilder().build())
								.build())
				.build();
		CreateSecretRequest request = CreateSecretRequest.newBuilder()
				.setParent(projectName.toString())
				.setSecretId(secretId)
				.setSecret(secret)
				.build();
		this.secretManagerServiceClient.createSecret(request);
	}

	/**
	 * Returns true if there already exists a secret under the GCP project with the
	 * {@code secretId}.
	 */
	private boolean secretExists(String secretId) {
		ProjectName projectName = ProjectName.of(this.projectIdProvider.getProjectId());
		ListSecretsPagedResponse listSecretsResponse = this.secretManagerServiceClient.listSecrets(projectName);

		return StreamSupport.stream(listSecretsResponse.iterateAll().spliterator(), false)
				.anyMatch(secret -> secret.getName().contains(secretId));
	}

	private ByteString convertToByteString(Object rawPayload) {
		ByteString payload;
		if (rawPayload instanceof byte[]) {
			payload = ByteString.copyFrom((byte[]) rawPayload);
		}
		else if (rawPayload instanceof String) {
			payload = ByteString.copyFromUtf8((String) rawPayload);
		}
		else {
			throw new IllegalArgumentException(
					"No support for handling payloads of type: " + rawPayload.getClass().getCanonicalName());
		}
		return payload;
	}
}
