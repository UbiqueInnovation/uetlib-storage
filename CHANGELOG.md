# CHANGELOG

## 1.0.1

### New Feature

- `OutputMetaData` now offers a `cache-control` constructor.
  If you were using `org.springframework.http.CacheControl` before `uetlib`, you can migrate by passing `cacheControl.getHeaderValue` instead.

## 1.0.0

### Breaking Changes

- `OutputMetaData` no longer has a constructor that accepts `org.springframework.http.CacheControl`.


