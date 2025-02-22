// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.schemas.code

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.util.io.Decompressor
import software.amazon.awssdk.services.schemas.SchemasClient
import software.amazon.awssdk.services.schemas.model.CodeGenerationStatus
import software.amazon.awssdk.services.schemas.model.ConflictException
import software.amazon.awssdk.services.schemas.model.DescribeCodeBindingRequest
import software.amazon.awssdk.services.schemas.model.GetCodeBindingSourceRequest
import software.amazon.awssdk.services.schemas.model.NotFoundException
import software.amazon.awssdk.services.schemas.model.PutCodeBindingRequest
import software.aws.toolkits.jetbrains.core.AwsClientManager
import software.aws.toolkits.jetbrains.core.credentials.AwsConnectionManager
import software.aws.toolkits.jetbrains.core.credentials.ConnectionSettings
import software.aws.toolkits.resources.message
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.zip.ZipFile

class SchemaCodeDownloader(
    private val generator: CodeGenerator,
    private val poller: CodeGenerationStatusPoller,
    private val downloader: CodeDownloader,
    private val extractor: CodeExtractor
) {
    fun downloadCode(
        schemaDownloadRequest: SchemaCodeDownloadRequestDetails,
        indicator: ProgressIndicator
    ): CompletionStage<File?> {
        val schemaName = schemaDownloadRequest.schema.name
        indicator.updateProgress(message("schemas.schema.download_code_bindings.notification.start", schemaName))

        return downloader.download(schemaDownloadRequest) // Try to get code directly
            .handle { downloadedSchemaCode, exception ->
                when (exception) {
                    null -> return@handle downloadedSchemaCode // Code is available, return
                    !is NotFoundException -> throw exception // Unexpected exception, throw
                }

                indicator.updateProgress(message("schemas.schema.download_code_bindings.notification.generating", schemaName))
                generator.generate(schemaDownloadRequest) // If the code generation status wasn't previously requested, trigger it now
                    .thenCompose { initialCodeGenerationStatus ->
                        poller.pollForCompletion(schemaDownloadRequest, initialCodeGenerationStatus) // Then, poll for completion
                    }
                    .thenCompose {
                        indicator.updateProgress(message("schemas.schema.download_code_bindings.notification.downloading", schemaName))
                        downloader.download(schemaDownloadRequest) // Download the code zip file
                    }
                    .toCompletableFuture().get()
            }
            .thenCompose { code ->
                indicator.updateProgress(message("schemas.schema.download_code_bindings.notification.extracting", schemaName))
                extractor.extractAndPlace(schemaDownloadRequest, code) // Extract and place in workspace
            }
    }

    private fun ProgressIndicator.updateProgress(newStatus: String) {
        text = newStatus
        isIndeterminate = true
    }

    companion object {
        fun create(project: Project): SchemaCodeDownloader {
            val connectionSettings = AwsConnectionManager.getInstance(project).connectionSettings()
                ?: throw IllegalStateException("Attempting to use SchemaCodeDownload without valid AWS connection")
            return create(connectionSettings)
        }

        fun create(connectionSettings: ConnectionSettings): SchemaCodeDownloader {
            val clientManager = AwsClientManager.getInstance()
            return SchemaCodeDownloader(
                CodeGenerator(clientManager.getClient(connectionSettings.credentials, connectionSettings.region)),
                CodeGenerationStatusPoller(clientManager.getClient(connectionSettings.credentials, connectionSettings.region)),
                CodeDownloader(clientManager.getClient(connectionSettings.credentials, connectionSettings.region)),
                CodeExtractor()
            )
        }
    }
}

class CodeGenerator(private val schemasClient: SchemasClient) {
    fun generate(schemaDownload: SchemaCodeDownloadRequestDetails): CompletionStage<CodeGenerationStatus> {
        val future = CompletableFuture<CodeGenerationStatus>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val request = PutCodeBindingRequest.builder()
                    .language(schemaDownload.language.apiValue)
                    .registryName(schemaDownload.schema.registryName)
                    .schemaName(schemaDownload.schema.name)
                    .schemaVersion(schemaDownload.version)
                    .build()

                val result = schemasClient.putCodeBinding(request)
                future.complete(result.status())
            } catch (e: ConflictException) {
                future.complete(CodeGenerationStatus.CREATE_IN_PROGRESS)
            } catch (e: Exception) {
                future.completeExceptionally(RuntimeException(message("schemas.schema.download_code_bindings.failed_to_generate"), e))
            }
        }
        return future
    }
}

class CodeGenerationStatusPoller(private val schemasClient: SchemasClient) {
    fun pollForCompletion(
        schemaDownload: SchemaCodeDownloadRequestDetails,
        initialCodeGenerationStatus: CodeGenerationStatus = CodeGenerationStatus.CREATE_IN_PROGRESS
    ): CompletionStage<String> {
        val future = CompletableFuture<String>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (initialCodeGenerationStatus != CodeGenerationStatus.CREATE_COMPLETE) {
                    schemasClient.waiter().waitUntilCodeBindingExists {
                        it.registryName(schemaDownload.schema.registryName)
                        it.schemaName(schemaDownload.schema.name)
                        it.schemaVersion(schemaDownload.version)
                        it.language(schemaDownload.language.apiValue)
                    }
                }

                future.complete(schemaDownload.schema.name)
            } catch (e: Exception) {
                future.completeExceptionally(RuntimeException(message("schemas.schema.download_code_bindings.failed_to_poll"), e))
            }
        }
        return future
    }

    fun getCurrentStatus(
        schemaDownload: SchemaCodeDownloadRequestDetails
    ): CompletionStage<CodeGenerationStatus> {
        val future = CompletableFuture<CodeGenerationStatus>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val request = DescribeCodeBindingRequest.builder()
                    .language(schemaDownload.language.apiValue)
                    .registryName(schemaDownload.schema.registryName)
                    .schemaName(schemaDownload.schema.name)
                    .schemaVersion(schemaDownload.version)
                    .build()

                val result = schemasClient.describeCodeBinding(request)

                future.complete(
                    result.status()
                )
            } catch (e: Exception) {
                future.completeExceptionally(RuntimeException(message("schemas.schema.download_code_bindings.failed_to_poll"), e))
            }
        }
        return future
    }
}

class CodeDownloader(private val schemasClient: SchemasClient) {
    fun download(
        schemaDownload: SchemaCodeDownloadRequestDetails
    ): CompletionStage<DownloadedSchemaCode> {
        val future = CompletableFuture<DownloadedSchemaCode>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val request = GetCodeBindingSourceRequest.builder()
                    .language(schemaDownload.language.apiValue)
                    .registryName(schemaDownload.schema.registryName)
                    .schemaName(schemaDownload.schema.name)
                    .schemaVersion(schemaDownload.version)
                    .build()

                val result = schemasClient.getCodeBindingSource(request)
                val zipContents = result.body()

                future.complete(DownloadedSchemaCode(zipContents.asByteBuffer()))
            } catch (e: NotFoundException) {
                future.completeExceptionally(e)
            } catch (e: Exception) {
                future.completeExceptionally(RuntimeException(message("schemas.schema.download_code_bindings.failed_to_download"), e))
            }
        }
        return future
    }
}

class CodeExtractor {
    fun extractAndPlace(
        request: SchemaCodeDownloadRequestDetails,
        downloadedSchemaCode: DownloadedSchemaCode
    ): CompletionStage<File?> {
        val zipContents = downloadedSchemaCode.zipContents
        val zipFileName = "${request.schema.registryName}.${request.schema.name}.${request.version}.${request.language.apiValue}.zip"

        val schemaCoreCodeFileName = request.schemaCoreCodeFileName()
        var schemaCoreCodeFile: File? = null

        val future = CompletableFuture<File?>()

        try {
            val codeZipDir = createTempDir()
            val codeZipFile = File(codeZipDir.path, zipFileName)

            FileOutputStream(codeZipFile).channel.use { fileChannel ->
                fileChannel.write(zipContents)
            }

            val destinationDirectory = File(request.destinationDirectory)
            validateNoFileCollisions(codeZipFile, destinationDirectory)

            val decompressor = Decompressor.Zip(codeZipFile)
                .overwrite(false)
                .postprocessor { file ->
                    if (schemaCoreCodeFile == null && file.name.equals(schemaCoreCodeFileName)) {
                        schemaCoreCodeFile = file
                    }
                }
            decompressor.extract(destinationDirectory)

            future.complete(schemaCoreCodeFile)
        } catch (e: Exception) {
            future.completeExceptionally(RuntimeException(message("schemas.schema.download_code_bindings.failed_to_extract"), e))
        }
        return future
    }

    // Ensure that the downloaded code hierarchy has no collisions with the destination directory
    private fun validateNoFileCollisions(codeZipFile: File, destinationDirectory: File) {
        ZipFile(codeZipFile).use { zipFile ->
            val zipEntries = zipFile.entries()
            Collections.list(zipEntries).forEach { zipEntry ->
                // Ignore directories because those can/will be merged
                if (!zipEntry.isDirectory) {
                    val intendedDestinationPath = Paths.get(destinationDirectory.path, zipEntry.name)
                    val intendedDestinationFile = intendedDestinationPath.toFile()
                    if (intendedDestinationFile.exists() && !intendedDestinationFile.isDirectory) {
                        throw SchemaCodeDownloadFileCollisionException(intendedDestinationFile.name)
                    }
                }
            }
        }
    }
}

class SchemaCodeDownloadFileCollisionException(fileName: String) :
    RuntimeException(message("schemas.schema.download_code_bindings.failed_to_extract_collision", fileName))
