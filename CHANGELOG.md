# CHANGELOG

## 3.0.0

- Move Java packages and class references from `ch.ubique.uetlib` to `ch.uetlib`.

### Breaking changes

- Move Java packages from `ch.ubique.backend` to `ch.uetlib`, including class-name resources. The combined OpenRewrite migration also renames known Spring configuration keys to `ch.uetlib.*`.
- Remove the inherited Ubique Artifactory repository and deployment destination; private consumers must configure their own registry.
- Add Apache-2.0 licensing for original code, preserve third-party licenses, and add public project metadata and contribution/security guidance.

- Move Maven coordinates from `ch.ubique:uetlib-*` to `ch.uetlib:uetlib-*`.
- Align uetlib parent, BOM and library references with the migration releases.

### Migration

After the migration releases and `uetlib-rewrite` are published, run this from the consuming project, then review the diff and run its tests:

```sh
mvn -U org.openrewrite.maven:rewrite-maven-plugin:6.46.1:run \
  -Drewrite.recipeArtifactCoordinates=ch.uetlib:uetlib-rewrite:RELEASE \
  -Drewrite.activeRecipes=ch.uetlib.MigrateToPublicUetlib
```

The recipe updates Maven coordinates/versions, Java references and known class-name resources. Review application-owned packages under the old namespace, Kotlin, reflection patterns, native-access module flags and persisted class names separately. Use `:dryRun` instead of `:run` to preview a patch.


## 2.4.1

### Improvements

- `MinioStorageClient` (and therefore `CloudflareR2StorageClient`) uses cheaper S3 operations:
    - `delete(path)` / `delete(path, false)` checks exact key with `HeadObject`, deletes with a single `DeleteObject`
      (free on R2), and falls back to `list` calls.
    - `exists(path)` checks exact key (`HeadObject`) and falls back to `list` calls.

### Fixes

- `MinioStorageClient` now throws `RetryableStorageClientException` instead of `IllegalStateException` for IO
  errors (e.g. connection refused, timeouts).
- `AzureBlobStorageClient` now throws `StorageClientException` instead of the SDK's unchecked exceptions (e.g.
  `BlobStorageException`) from `write`, `delete`, `list` and `exists`.

### Behavior changes

- `StorageClient.delete` returns `true` if something was deleted and `false` if the path did not exist.
  `MinioStorageClient` and `AmazonS3StorageClient` had always returned `true`.
- Non-recursive delete of an existing key now succeeds even if other keys share it as prefix (e.g. `foo` next to
  `foo.bak`); previously it threw "cannot delete non-empty directory".
    - Non-recursive `delete` and `exists` need read permission (`s3:GetObject`) (`HeadObject`). Delete/write-only
      credentials fail. On AWS S3, credentials without `s3:ListBucket` get `403` instead of `404` for missing keys,
      which is reported as an error.
- `write` and the `copy` destination reject empty paths and paths ending in `/` with a
  `NonRetryableStorageClientException`, for all implementations. Previously S3 and Azure stored a "folder marker"
  object, while the file system client silently wrote to the path without the trailing `/`.

## 2.4.0

- Add native single-object `StorageClient.copy` for S3, Azure block blobs and local files.

## 2.3.5

### Fixes

- Increase the default OkHttp dispatcher limits used by `MinioStorageClient` to avoid same-host
  dispatcher starvation when failed multipart uploads enqueue abort requests from OkHttp callbacks.

## 2.3.4

- allow processing image directly from buffered image to avoid decoding the original image multiple times.
  `processImageFromOriginalBytesConvertToWebp` -> `processImageFromDecodedOriginalConvertToWebp`

## 2.3.3

- latest uetlib parent
- version updates

## 2.3.2

- updated actions to not run tests twice for sonar
- update uetlib version

## 2.3.1

### Fixes

- Fixed minio (non)-retryable exception handling

## 2.3.0

### Improvements

- migrate to minio 9
- updated `uetlib-parent` to
  `2026.07.0`: [Changelog](https://github.com/UbiqueInnovation/uetlib-parent/blob/main/CHANGELOG.md#2026070)

## 2.2.1

- Added `StorageClient.fileProvider(String path)` for convience. `FileSystemStorageClient` gives a
  direct pointer to the file to avoid creating a temporary file from the input stream.

## 2.2.0

### Improvements

- Added `StorageClient#getSignedUploadUrl` for uploading files without passing though the backend.

## 2.1.0

### Fixes

- Null-marked it all
- Fix S3 storage client list, now does paging to load all objects, as opposed to just the first page
  of max 1000
  elements

## 2.0.4

### Improvements (Image Storage/Processing)

- Image size is now directly linked to image variant (can be read from outside)
- Added original image upload method that takes byte[]
- Added method overloads for processing that take image bytes instead of input stream (makes retry
  possible)
- Image processing option to convert the processed images to webp

## 2.0.0

### Breaking Changes

- Replace deprecated `spring-retry` with the new Retry API integrated in spring-core.

## 1.3.2

### Fixes

- Fix S3 storage client, input stream closed too early

## 1.3.1

### Improvements

- use `uetlib-self-bom` instead of fixing uetlib versions individually
- update `uetlib-parent` to 1.3.0

## 1.3.0

### Fixes

Make sure to use the correct header date formatter for `expires`, `best-before`, etc. in output
metadata.
`RFC_1123_DATE_TIME` does not enforce two digits for day of month, as says it should.

## 1.2.5

### Fixes

- Catch `InvocationTargetException` in `StorageInvocationHandler` and correctly propagate the
  underlying exception.

## 1.2.4

### Improvements

- Avoid slowly finding and deleting all empty directories in `FileSystemStorageClient.delete`, only
  remove empty directories that are parents of the item being deleted.

## 1.2.3

### Improvements

- Retry write in S3 storage if the `Output` can be retried.

## 1.2.2

### Improvements

- Split `StorageObjectTypeResolver` into `StorageObjectParameterResolver` and moved the other half
  to
  `uetlib-storage-spring-boot`.

## 1.2.0

### Improvements

- Latest uetlib dependencies with Spring Boot 3.5 support

## 1.1.5

- Better exception handling for `AmazonS3StorageClient`

## 1.1.4

- Added `PrefixStorageClient` to allow writing to sub-paths. For example, we can use a single S3
  Bucket and create multiple storage clients writing to subfolders of the bucket.
- Fixed some more inconsistencies between `FileSystemStorageClient` and all other implementations.
  convenient.
- `FileSystemStorageClient` write uncompressed is the default again, since this is way more
  convenient.
- Removed progress logger from `AmazonS3StorageClient`. It was way too verbose.

## 1.1.3

- Handle files and byte payloads explicitly for the AmazonS3StorageClient. This ensures the AWS
  internal retry mechanism works properly.

## 1.0.4

- Made gzip writing and reading more consistent between different storage clients
- Made reading more resilient to seemingly but not actually compressed resources.

## 1.0.3

- Fixed delete of azure-client. Made sure the file system client behaves consistently with the other
  clients.

## 1.0.2

- Fixed Azure Signed URL

## 1.0.1

### New Feature

- `OutputMetaData` now offers a `cache-control` constructor.
  If you were using `org.springframework.http.CacheControl` before `uetlib`, you can migrate by
  passing `cacheControl.getHeaderValue` instead.

## 1.0.0

### Breaking Changes

- `OutputMetaData` no longer has a constructor that accepts `org.springframework.http.CacheControl`.
