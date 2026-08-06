# Examples

These examples use current tool signatures and response behavior.

## 1) Entry-point mapping

```python
pkg_tree = await get_package_tree()
main = await get_main_activity_class()
app_classes = await get_main_application_classes_names()
```

Then ask the LLM to rank likely auth/network/storage classes.

## 2) Keyword search with scopes

```python
crypto = await search_classes_by_keyword(
    search_term="cipher",
    search_in="method,code",
    count=50,
)
```

```python
comments = await search_classes_by_keyword(
    search_term="todo",
    search_in="comment",
    count=20,
)
```

## 3) Method lookup in known class

```python
method = await get_method_by_name(
    class_name="com.example.auth.AuthManager",
    method_name="login",
    method_signature="(Ljava/lang/String;Ljava/lang/String;)Z",
)
```

## 4) Xref tracing

```python
refs = await get_xrefs_to_method(
    class_name="com.example.auth.AuthManager",
    method_name="login",
    count=100,
)
```

`refs` is paginated with `items` containing `class` and `method`.

## 5) Resource analysis

```python
manifest = await get_android_manifest()
exported_activities = await get_manifest_component("activity", only_exported=True)
strings = await get_strings(offset=0, count=200)
```

## 6) Refactor flow

```python
await rename_class("a.b.c", "CryptoHelper")
await rename_field("com.example.Config", "a", "apiBaseUrl")
await rename_variable("com.example.Auth", "login", "v0", "username")
```

Method rename (current signature):

```python
await rename_method("a", "initialize", class_name="com.example.MainActivity")
# Better disambiguation if overloaded:
await rename_method(
    "a",
    "initialize",
    class_name="com.example.MainActivity",
    method_signature="(Landroid/content/Context;)V",
)
```

## 7) Debug snapshot

```python
stack = await debug_get_stack_frames()
threads = await debug_get_threads()
variables = await debug_get_variables()
```

Use these together for runtime flow analysis while paused.

## 8) Cache maintenance

```python
stats_before = await get_cache_stats()
cleared = await clear_cache()
stats_after = await get_cache_stats()
```

Useful after switching APKs or after bulk decompilation requests.
