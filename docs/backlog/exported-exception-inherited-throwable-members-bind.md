# Inherited `Throwable` members bind on every exported exception root

Found with issue #612 (ADR-241). The binding is verified in the generated C# for the `StoreException` fixture (`PrintStackTrace()` on the root); the cause is read from source and not run on `main`.

An exported exception class with no exported superclass binds every inherited member, because the membership rule admits all inherited members of a class with no exported superclass. So `printStackTrace` binds as `PrintStackTrace()` on every exported exception root, and the build warns `SKIPPED_OPT_IN_MARKER` for `getStackTrace` (`ExperimentalNativeApi`) on every one, which a library author cannot silence.

The likely fix is to exclude the inherited `Throwable` members the way `message` and `cause` are excluded. This predates ADR-241, which only made the exposure visible by turning these classes into exception types.
