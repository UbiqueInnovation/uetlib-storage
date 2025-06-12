# CHANGELOG

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


