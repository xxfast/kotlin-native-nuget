using System.Collections.Immutable;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace NugetMetadataReader;

// ---------------------------------------------------------------------------
// CLI entry point
// ---------------------------------------------------------------------------

internal static class Program
{
    private static int Main(string[] args)
    {
        var parsed = CliArgs.Parse(args);
        if (parsed is null)
        {
            Console.Error.WriteLine(
                "Usage: NugetMetadataReader --package <id> <dllPath> [--package ...] " +
                "[--include <ns>...] [--exclude <ns>...]");
            return 1;
        }

        var assemblies = new List<RirAssembly>();

        foreach (var pkg in parsed.Packages)
        {
            if (!File.Exists(pkg.DllPath))
            {
                Console.Error.WriteLine($"error: DLL not found: {pkg.DllPath}");
                return 1;
            }

            try
            {
                var assembly = AssemblyExtractor.Extract(
                    pkg.PackageId, pkg.DllPath, pkg.Includes, pkg.Excludes);
                assemblies.Add(assembly);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"error: failed to read '{pkg.DllPath}': {ex.Message}");
                return 1;
            }
        }

        var file = new RirFile(assemblies);
        var json = JsonSerializer.Serialize(file, RirJsonContext.Default.RirFile);
        Console.WriteLine(json);
        return 0;
    }
}

// ---------------------------------------------------------------------------
// CLI argument parsing
// ---------------------------------------------------------------------------

internal sealed class PackageSpec
{
    public string PackageId { get; }
    public string DllPath { get; }
    public List<string> Includes { get; } = new();
    public List<string> Excludes { get; } = new();

    public PackageSpec(string packageId, string dllPath)
    {
        PackageId = packageId;
        DllPath = dllPath;
    }
}

internal sealed class CliArgs
{
    public List<PackageSpec> Packages { get; } = new();

    public static CliArgs? Parse(string[] args)
    {
        var result = new CliArgs();
        PackageSpec? current = null;
        int i = 0;

        while (i < args.Length)
        {
            switch (args[i])
            {
                case "--package":
                    if (i + 2 >= args.Length) return null;
                    if (current is not null) result.Packages.Add(current);
                    current = new PackageSpec(args[i + 1], args[i + 2]);
                    i += 3;
                    break;

                case "--include":
                    i++;
                    while (i < args.Length && !args[i].StartsWith("--"))
                        current!.Includes.Add(args[i++]);
                    break;

                case "--exclude":
                    i++;
                    while (i < args.Length && !args[i].StartsWith("--"))
                        current!.Excludes.Add(args[i++]);
                    break;

                default:
                    Console.Error.WriteLine($"error: unknown argument: {args[i]}");
                    return null;
            }
        }

        if (current is not null) result.Packages.Add(current);
        if (result.Packages.Count == 0) return null;
        return result;
    }
}

// ---------------------------------------------------------------------------
// Core extractor
// ---------------------------------------------------------------------------

internal static class AssemblyExtractor
{
    private static readonly string[] AsyncTypeNames =
    [
        "System.Threading.Tasks.Task",
        "System.Threading.Tasks.Task`1",
        "System.Threading.Tasks.ValueTask",
        "System.Threading.Tasks.ValueTask`1",
        "System.Collections.Generic.IAsyncEnumerable`1",
    ];

    public static RirAssembly Extract(
        string packageId,
        string dllPath,
        IReadOnlyList<string> includes,
        IReadOnlyList<string> excludes)
    {
        using var stream = File.OpenRead(dllPath);
        using var peReader = new PEReader(stream);

        var mr = peReader.GetMetadataReader();
        var assemblyName = GetAssemblyName(mr);

        // ADR-053: computed once, globally, before any per-type processing. This is the signal
        // for "whole assembly is nullable-oblivious" vs. "an oblivious island inside an otherwise
        // annotated assembly" — see the post-processing step below.
        var assemblyHasAnyNullableAnnotation = NullabilityHelpers.AssemblyHasAnyNullableAnnotation(mr);

        // Group type definitions by namespace, applying include/exclude filters.
        var namespaceMap = new Dictionary<string, List<TypeDefinitionHandle>>(StringComparer.Ordinal);
        var diagnostics = new List<RirDiagnostic>();

        foreach (var handle in mr.TypeDefinitions)
        {
            var typeDef = mr.GetTypeDefinition(handle);

            // Must be public (TypeAttributes.Public = 0x00000001).
            // Note: nested public types have TypeAttributes.NestedPublic = 0x00000002.
            // v1 scope: top-level public types only.
            if ((typeDef.Attributes & System.Reflection.TypeAttributes.VisibilityMask)
                != System.Reflection.TypeAttributes.Public)
                continue;

            // Skip the synthetic <Module> type.
            var typeName = mr.GetString(typeDef.Name);
            if (typeName == "<Module>") continue;

            var ns = mr.GetString(typeDef.Namespace);
            if (!IsNamespaceIncluded(ns, includes, excludes)) continue;

            if (!namespaceMap.TryGetValue(ns, out var list))
            {
                list = new List<TypeDefinitionHandle>();
                namespaceMap[ns] = list;
            }

            list.Add(handle);
        }

        // Build the set of bound handle type full names before processing methods, so the
        // SignatureDecoder can emit RirObjectHandleType for parameters and returns that
        // reference them (ADR-051).
        var boundHandleTypeNames = CollectBoundHandleTypeNames(mr, namespaceMap);
        var enumTypes = CollectEnumTypes(mr, namespaceMap);
        // ADR-056: value types that are not enums are struct candidates. This must be collected
        // BEFORE ProcessType runs so ProcessType can redirect every non-enum value type away from
        // the class fall-through (Constraint 3's bug: today every struct is emitted as a class).
        var structTypes = CollectStructTypes(mr, namespaceMap, enumTypes);

        var rirNamespaces = new List<RirNamespace>();

        foreach (var (ns, typeHandles) in namespaceMap.OrderBy(kv => kv.Key))
        {
            var rirTypes = new List<RirType>();

            foreach (var typeHandle in typeHandles)
            {
                var typeDef = mr.GetTypeDefinition(typeHandle);
                var (rirType, typeDiagnostics) = ProcessType(
                    mr, typeHandle, typeDef, boundHandleTypeNames, enumTypes, structTypes);
                if (rirType is not null) rirTypes.Add(rirType);
                diagnostics.AddRange(typeDiagnostics);
            }

            if (rirTypes.Count > 0)
                rirNamespaces.Add(new RirNamespace(ns, rirTypes));
        }

        // ADR-053: collapse per-member oblivious diagnostics into a single assembly-level one when
        // the WHOLE assembly is oblivious — a per-member warning for every single bound member of a
        // legacy package would print hundreds of lines and train users to ignore the log. An
        // oblivious *island* inside an otherwise-annotated assembly keeps its per-member diagnostics
        // as collected above (they are rare, precise, and actionable).
        if (!assemblyHasAnyNullableAnnotation)
        {
            diagnostics.RemoveAll(d => d.Kind == "info_oblivious_nullability");
            diagnostics.Add(new RirDiagnostic(
                kind: "info_oblivious_nullability",
                typeName: "",
                memberName: "",
                memberSignature: assemblyName,
                reason: $"assembly `{assemblyName}` carries no NullableAttribute/NullableContextAttribute " +
                    "anywhere — this is a legacy (pre-nullable-reference-types) package. Every reference " +
                    "type binds non-null.",
                hint: "A null arriving from this package where the binding assumes non-null throws " +
                    "IllegalStateException at the bridge, naming the member."));
        }

        return new RirAssembly(packageId, assemblyName, rirNamespaces, diagnostics);
    }

    /// <summary>
    /// Collects the fully-qualified names of all bound, wrapper-eligible types: public,
    /// non-interface, non-static (not abstract+sealed), non-ref-struct, non-value-type classes
    /// whose namespace is already in the bound set. These are the types that may cross the bridge
    /// as opaque GCHandle pointers (ADR-051 <c>RirObjectHandleType</c>).
    /// </summary>
    private static HashSet<string> CollectBoundHandleTypeNames(
        MetadataReader mr,
        Dictionary<string, List<TypeDefinitionHandle>> namespaceMap)
    {
        var names = new HashSet<string>(StringComparer.Ordinal);

        foreach (var (ns, typeHandles) in namespaceMap)
        {
            foreach (var typeHandle in typeHandles)
            {
                var typeDef = mr.GetTypeDefinition(typeHandle);
                bool isInterface = (typeDef.Attributes & System.Reflection.TypeAttributes.ClassSemanticsMask)
                                   == System.Reflection.TypeAttributes.Interface;
                // A C# static class is `abstract sealed` in ECMA-335 metadata (ADR-051).
                bool isStaticClass =
                    (typeDef.Attributes & System.Reflection.TypeAttributes.Abstract) != 0 &&
                    (typeDef.Attributes & System.Reflection.TypeAttributes.Sealed) != 0;

                if (isInterface || isStaticClass) continue;
                if (MetadataHelpers.IsValueType(mr, typeDef)) continue;
                if (MetadataHelpers.IsRefStructType(mr, typeDef)) continue;

                var typeName = mr.GetString(typeDef.Name);
                var fullName = string.IsNullOrEmpty(ns) ? typeName : $"{ns}.{typeName}";
                names.Add(fullName);
            }
        }

        return names;
    }

    // Reverse enum v1 is intentionally narrow: only public, top-level default-int enums with
    // unique contiguous values 0..N-1. Everything else remains outside the bridgeable subset and
    // is reported as an ADR-043-style diagnostic rather than being silently treated as a class.
    private static Dictionary<string, EnumExtraction> CollectEnumTypes(
        MetadataReader mr,
        Dictionary<string, List<TypeDefinitionHandle>> namespaceMap)
    {
        var result = new Dictionary<string, EnumExtraction>(StringComparer.Ordinal);

        foreach (var (ns, typeHandles) in namespaceMap)
        {
            foreach (var typeHandle in typeHandles)
            {
                var typeDef = mr.GetTypeDefinition(typeHandle);
                if (!MetadataHelpers.IsEnum(mr, typeDef)) continue;

                var name = mr.GetString(typeDef.Name);
                var fullName = string.IsNullOrEmpty(ns) ? name : $"{ns}.{name}";
                result[fullName] = ExtractEnum(mr, typeDef, name);
            }
        }

        return result;
    }

    private static EnumExtraction ExtractEnum(MetadataReader mr, TypeDefinition typeDef, string name)
    {
        bool isFlags = typeDef.GetCustomAttributes().Any(handle =>
            MetadataHelpers.GetCustomAttributeTypeName(mr, mr.GetCustomAttribute(handle))
                == "System.FlagsAttribute");
        if (isFlags)
            return EnumExtraction.Unsupported("[Flags] enums are not supported by the ordinal enum bridge");

        FieldDefinition? underlyingField = null;
        var entries = new List<RirEnumEntry>();

        foreach (var handle in typeDef.GetFields())
        {
            var field = mr.GetFieldDefinition(handle);
            var fieldName = mr.GetString(field.Name);
            if (fieldName == "value__")
            {
                underlyingField = field;
                continue;
            }

            if ((field.Attributes & System.Reflection.FieldAttributes.Literal) == 0)
                return EnumExtraction.Unsupported($"enum member `{fieldName}` is not a literal");
            if (field.GetDefaultValue().IsNil)
                return EnumExtraction.Unsupported($"enum member `{fieldName}` has no constant value");

            var constant = mr.GetConstant(field.GetDefaultValue());
            if (constant.TypeCode != ConstantTypeCode.Int32)
                return EnumExtraction.Unsupported("enum underlying type must be the default `int`");

            var ordinal = mr.GetBlobReader(constant.Value).ReadInt32();
            entries.Add(new RirEnumEntry(fieldName, ordinal));
        }

        if (underlyingField is null)
            return EnumExtraction.Unsupported("enum has no underlying value__ field");

        try
        {
            var underlying = underlyingField.Value.DecodeSignature(new SignatureDecoder(mr), genericContext: null);
            if (underlying.TypeRef is not RirPrimitiveType { Name: "int" })
                return EnumExtraction.Unsupported("enum underlying type must be the default `int`");
        }
        catch (BadImageFormatException)
        {
            return EnumExtraction.Unsupported("enum underlying type could not be decoded");
        }

        // Validate the SET of values, not their positions: metadata yields fields in declaration
        // order, so `enum Priority { High = 2, Low = 0, Medium = 1 }` is a perfectly supported
        // enum whose fields simply arrive out of order. N distinct values, each within [0, N-1],
        // is exactly "unique and contiguous from 0 through N-1"; negatives, gaps and duplicates
        // all still fail this test.
        var ordinals = new HashSet<int>();
        foreach (var entry in entries)
        {
            var isInRange = entry.Ordinal >= 0 && entry.Ordinal < entries.Count;
            if (!isInRange || !ordinals.Add(entry.Ordinal))
            {
                return EnumExtraction.Unsupported(
                    "enum values must be unique and contiguous from 0 through N-1");
            }
        }

        // The Kotlin generator emits entries in list order and marshals by Kotlin `.ordinal`, so
        // the list must be in ordinal order or the two sides of the ABI address different entries.
        entries.Sort((left, right) => left.Ordinal.CompareTo(right.Ordinal));

        return EnumExtraction.Supported(new RirEnum(name, entries));
    }

    // ADR-056 Decision 3a: every public, top-level, non-enum value type is a struct candidate.
    // This collects ALL of them — including the ones that fail the bridgeable-shape rules — so
    // ProcessType can redirect every one of them away from the class fall-through (the verified
    // Constraint 3 bug: today every struct shape is emitted as `RirClass`). Ref structs and
    // generic structs are collected here too (and marked Unsupported) for the same reason, even
    // though a *reference* to one from another member's signature is separately diagnosed earlier
    // by SignatureDecoder (`skipped_ref_struct`, `skipped_open_generic`).
    private static Dictionary<string, StructExtraction> CollectStructTypes(
        MetadataReader mr,
        Dictionary<string, List<TypeDefinitionHandle>> namespaceMap,
        IReadOnlyDictionary<string, EnumExtraction> enumTypes)
    {
        var result = new Dictionary<string, StructExtraction>(StringComparer.Ordinal);

        foreach (var (ns, typeHandles) in namespaceMap)
        {
            foreach (var typeHandle in typeHandles)
            {
                var typeDef = mr.GetTypeDefinition(typeHandle);
                if (!MetadataHelpers.IsValueType(mr, typeDef)) continue;
                if (MetadataHelpers.IsEnum(mr, typeDef)) continue;

                var name = mr.GetString(typeDef.Name);
                var fullName = string.IsNullOrEmpty(ns) ? name : $"{ns}.{name}";
                result[fullName] = ExtractStruct(mr, typeDef, name, enumTypes);
            }
        }

        return result;
    }

    /// <summary>
    /// Applies the ADR-056 Decision 3a "Shape A" rules: exactly one public instance constructor
    /// (with at least one parameter) whose parameters each match, case-insensitively, a public
    /// readable instance property of the same (primitive/string/bound-enum) type, and whose
    /// parameter count equals the struct's non-static instance field count (rule 5 — the proxy for
    /// "the constructor covers all stored state").
    /// </summary>
    private static StructExtraction ExtractStruct(
        MetadataReader mr,
        TypeDefinition typeDef,
        string name,
        IReadOnlyDictionary<string, EnumExtraction> enumTypes)
    {
        if (typeDef.GetGenericParameters().Count > 0)
            return StructExtraction.Unsupported(
                "generic struct — open/closed generic structs are not bridgeable in v1");

        if (MetadataHelpers.IsRefStructType(mr, typeDef))
            return StructExtraction.Unsupported(
                "ref struct — stack-only (IsByRefLike) and cannot cross the C ABI (ADR-043)");

        var ctorCandidates = new List<MethodDefinitionHandle>();
        foreach (var handle in typeDef.GetMethods())
        {
            var method = mr.GetMethodDefinition(handle);
            if ((method.Attributes & System.Reflection.MethodAttributes.MemberAccessMask)
                != System.Reflection.MethodAttributes.Public) continue;
            if ((method.Attributes & System.Reflection.MethodAttributes.Static) != 0) continue;
            if (mr.GetString(method.Name) != ".ctor") continue;
            ctorCandidates.Add(handle);
        }

        if (ctorCandidates.Count == 0)
            return StructExtraction.Unsupported(
                "no public instance constructor (Shape B — public fields / settable auto-properties " +
                "— is deferred)");
        if (ctorCandidates.Count > 1)
            return StructExtraction.Unsupported(
                $"{ctorCandidates.Count} public constructors — overload set, the same ADR-043 " +
                "ceiling that excludes multi-constructor classes");

        var ctorDef = mr.GetMethodDefinition(ctorCandidates[0]);

        // Component-type decoding deliberately passes an EMPTY bound-handle set and no struct map:
        // a class-typed component is never in the v1 vocabulary (no bound-handle set to resolve
        // against), and a nested-struct component is always rejected (Scope: "nested struct
        // components deferred") regardless of whether the referenced struct would otherwise be
        // bridgeable — passing no struct map means GetTypeFromDefinition can never resolve one to
        // RirStructType here.
        var componentDecoder = new SignatureDecoder(mr, boundHandleTypeNames: null, enumTypes);

        MethodSignature<TypeRefOrDiag> ctorSig;
        try
        {
            ctorSig = ctorDef.DecodeSignature(componentDecoder, genericContext: null);
        }
        catch (BadImageFormatException)
        {
            return StructExtraction.Unsupported("constructor signature could not be decoded");
        }

        if (ctorSig.ParameterTypes.Length == 0)
            return StructExtraction.Unsupported(
                "public constructor has no parameters — zero components");

        int instanceFieldCount = typeDef.GetFields().Count(fieldHandle =>
            (mr.GetFieldDefinition(fieldHandle).Attributes & System.Reflection.FieldAttributes.Static) == 0);

        if (instanceFieldCount != ctorSig.ParameterTypes.Length)
            return StructExtraction.Unsupported(
                $"struct has {instanceFieldCount} stored instance field(s) but its constructor " +
                $"takes {ctorSig.ParameterTypes.Length} parameter(s) — state would be silently dropped");

        // Public, readable, non-static instance properties, keyed case-insensitively (Constraint 4:
        // ctor parameter `x` vs. property `X`).
        var properties = new Dictionary<string, (TypeRefOrDiag Decoded, string ReadName)>(
            StringComparer.OrdinalIgnoreCase);

        foreach (var propHandle in typeDef.GetProperties())
        {
            var propDef = mr.GetPropertyDefinition(propHandle);
            var accessors = propDef.GetAccessors();
            if (accessors.Getter.IsNil) continue;

            var getterDef = mr.GetMethodDefinition(accessors.Getter);
            if ((getterDef.Attributes & System.Reflection.MethodAttributes.MemberAccessMask)
                != System.Reflection.MethodAttributes.Public) continue;
            if ((getterDef.Attributes & System.Reflection.MethodAttributes.Static) != 0) continue;

            TypeRefOrDiag propType;
            try
            {
                propType = propDef.DecodeSignature(componentDecoder, genericContext: null).ReturnType;
            }
            catch (BadImageFormatException)
            {
                continue;
            }

            var propName = mr.GetString(propDef.Name);
            properties[propName] = (propType, propName);
        }

        var paramHandlesBySeq = MapParameterHandlesBySequenceNumber(mr, ctorDef);
        var components = new List<RirStructComponent>();

        for (int i = 0; i < ctorSig.ParameterTypes.Length; i++)
        {
            int seq = i + 1;
            string paramName = "arg" + i;
            if (paramHandlesBySeq.TryGetValue(seq, out var ph))
            {
                var pn = mr.GetString(mr.GetParameter(ph).Name);
                if (!string.IsNullOrEmpty(pn)) paramName = pn;
            }

            if (!properties.TryGetValue(paramName, out var match))
                return StructExtraction.Unsupported(
                    $"constructor parameter `{paramName}` has no matching public readable property");

            var (propDecoded, readName) = match;
            if (propDecoded.TypeRef is null)
            {
                var reason = propDecoded.Diagnostic?.Reason
                    ?? $"component type `{propDecoded.RawTypeName}` is not bridgeable";
                return StructExtraction.Unsupported($"property `{readName}`: {reason}");
            }

            var ctorParamDecoded = ctorSig.ParameterTypes[i];
            if (ctorParamDecoded.TypeRef is null)
            {
                var reason = ctorParamDecoded.Diagnostic?.Reason
                    ?? $"component type `{ctorParamDecoded.RawTypeName}` is not bridgeable";
                return StructExtraction.Unsupported($"constructor parameter `{paramName}`: {reason}");
            }

            if (!TypeRefsStructurallyEqual(ctorParamDecoded.TypeRef, propDecoded.TypeRef))
                return StructExtraction.Unsupported(
                    $"constructor parameter `{paramName}` and property `{readName}` have different types");

            components.Add(new RirStructComponent(paramName, readName, ctorParamDecoded.TypeRef));
        }

        return StructExtraction.Supported(new RirStruct(name, components));
    }

    private static bool TypeRefsStructurallyEqual(RirTypeRef a, RirTypeRef b) => (a, b) switch
    {
        (RirPrimitiveType pa, RirPrimitiveType pb) => pa.Name == pb.Name,
        (RirStringType, RirStringType) => true,
        (RirEnumType ea, RirEnumType eb) => ea.Namespace == eb.Namespace && ea.Name == eb.Name,
        _ => false,
    };

    private static (RirType? Type, IEnumerable<RirDiagnostic> Diagnostics) ProcessType(
        MetadataReader mr,
        TypeDefinitionHandle typeHandle,
        TypeDefinition typeDef,
        HashSet<string> boundHandleTypeNames,
        IReadOnlyDictionary<string, EnumExtraction> enumTypes,
        IReadOnlyDictionary<string, StructExtraction> structTypes)
    {
        var typeName = mr.GetString(typeDef.Name);
        var ns = mr.GetString(typeDef.Namespace);
        var fullName = string.IsNullOrEmpty(ns) ? typeName : $"{ns}.{typeName}";
        if (enumTypes.TryGetValue(fullName, out var enumType))
        {
            if (enumType.Enum is not null) return (enumType.Enum, Array.Empty<RirDiagnostic>());

            return (null, new[] { new RirDiagnostic(
                kind: "skipped_unsupported_enum",
                typeName: typeName,
                memberName: typeName,
                memberSignature: fullName,
                reason: enumType.Reason!,
                hint: "Use a non-[Flags] default-int enum with unique contiguous values from 0 through N-1.") });
        }

        // ADR-056: a value type that is not an enum is a struct candidate and must not fall
        // through to class processing below (Constraint 3's verified bug). Struct members are not
        // enumerated in v1 — a supported struct emits ONLY its RirStruct node.
        if (structTypes.TryGetValue(fullName, out var structType))
        {
            if (structType.Struct is not null) return (structType.Struct, Array.Empty<RirDiagnostic>());

            return (null, new[] { new RirDiagnostic(
                kind: "skipped_unsupported_struct",
                typeName: typeName,
                memberName: typeName,
                memberSignature: fullName,
                reason: structType.Reason!,
                hint: "See ADR-056 Decision 3a: a bridgeable struct has exactly one public " +
                    "constructor covering all stored state, with primitive/string/bound-enum " +
                    "components matching the constructor parameters case-insensitively.") });
        }

        var isInterface = (typeDef.Attributes & System.Reflection.TypeAttributes.ClassSemanticsMask)
                          == System.Reflection.TypeAttributes.Interface;
        var isAbstract = (typeDef.Attributes & System.Reflection.TypeAttributes.Abstract) != 0;
        // A C# static class is `abstract sealed` in ECMA-335 metadata (ADR-051).
        var isSealed = (typeDef.Attributes & System.Reflection.TypeAttributes.Sealed) != 0;
        var isStatic = isAbstract && isSealed;

        var methods = new List<RirMethod>();
        var diagnostics = new List<RirDiagnostic>();

        // --- Methods ---
        // Group by name first to detect overload sets.
        var methodGroups = new Dictionary<string, List<MethodDefinitionHandle>>(StringComparer.Ordinal);
        // ADR-052: public instance `.ctor` candidates, collected separately from methodGroups —
        // a constructor has no return type and maps to a distinct RirConstructor node, not RirMethod.
        var ctorCandidates = new List<MethodDefinitionHandle>();

        foreach (var handle in typeDef.GetMethods())
        {
            var method = mr.GetMethodDefinition(handle);
            // MethodAttributes.Public (0x6) lives inside the 3-bit MemberAccessMask (0x7); testing
            // `& Public` for non-zero also matches Assembly (0x3, internal) and Family (0x4,
            // protected), since both AND to a non-zero value against 0x6. Must mask first, then
            // compare equality against Public — otherwise internal/protected methods leak through
            // as if they were public. This check must run BEFORE the narrowed `.ctor` admission
            // below so non-public constructors stay excluded (ADR-052).
            if ((method.Attributes & System.Reflection.MethodAttributes.MemberAccessMask)
                != System.Reflection.MethodAttributes.Public) continue;

            if ((method.Attributes & System.Reflection.MethodAttributes.SpecialName) != 0)
            {
                // ADR-052: narrow the SpecialName skip to admit public instance constructors as
                // candidates. Everything else SpecialName (.cctor, get_X/set_X accessors) stays
                // skipped, matching the ADR-051 scope this replaces.
                var specialName = mr.GetString(method.Name);
                bool isPublicInstanceCtor = specialName == ".ctor"
                    && (method.Attributes & System.Reflection.MethodAttributes.Static) == 0;
                if (isPublicInstanceCtor) ctorCandidates.Add(handle);
                continue;
            }

            var name = mr.GetString(method.Name);
            if (!methodGroups.TryGetValue(name, out var group))
            {
                group = new List<MethodDefinitionHandle>();
                methodGroups[name] = group;
            }

            group.Add(handle);
        }

        foreach (var (methodName, group) in methodGroups)
        {
            if (group.Count > 1)
            {
                // Overload set: skip all, one diagnostic.
                var sig = $"{methodName}({DescribeFirstParams(mr, mr.GetMethodDefinition(group[0]))}) [+{group.Count - 1} overloads]";
                diagnostics.Add(new RirDiagnostic(
                    kind: "skipped_overload_set",
                    typeName: typeName,
                    memberName: methodName,
                    memberSignature: sig,
                    reason: $"overload set — {group.Count} overloads of `{methodName}` cannot be uniquely exported to C",
                    hint: "Add a C# adapter shim to expose each overload under a distinct name."));
                continue;
            }

            var methodHandle = group[0];
            var methodDef = mr.GetMethodDefinition(methodHandle);
            var (rirMethod, methodDiagnostic, obliviousDiagnostic) = TryMapMethod(
                mr, typeHandle, methodHandle, methodDef, typeName, isInterface, boundHandleTypeNames, enumTypes, structTypes);
            if (rirMethod is not null)
            {
                methods.Add(rirMethod);
                if (obliviousDiagnostic is not null) diagnostics.Add(obliviousDiagnostic);
            }
            else if (methodDiagnostic is not null)
                diagnostics.Add(methodDiagnostic);
        }

        // --- Constructors (ADR-052) ---
        var constructors = new List<RirConstructor>();

        // Only a non-static, non-abstract class can carry an emittable RirConstructor: a static
        // class has no instance `.ctor`; an abstract class's `.ctor` is `protected`, which is
        // already excluded by the Public check above — this guard is belt-and-braces for that
        // invariant. Interfaces have no constructors at all.
        bool canHaveConstructor = !isInterface && !isStatic && !isAbstract;

        if (canHaveConstructor && ctorCandidates.Count == 1)
        {
            var (rirCtor, ctorDiagnostic, ctorObliviousDiagnostic) = TryMapConstructor(
                mr, typeHandle, ctorCandidates[0], typeName, boundHandleTypeNames, enumTypes, structTypes);
            if (rirCtor is not null)
            {
                constructors.Add(rirCtor);
                if (ctorObliviousDiagnostic is not null) diagnostics.Add(ctorObliviousDiagnostic);
            }
            else if (ctorDiagnostic is not null)
                diagnostics.Add(ctorDiagnostic);
        }
        else if (canHaveConstructor && ctorCandidates.Count > 1)
        {
            // Overload set: skip all, one diagnostic — same rule already applied to methods.
            var sig = $".ctor({DescribeFirstParams(mr, mr.GetMethodDefinition(ctorCandidates[0]))}) [+{ctorCandidates.Count - 1} overloads]";
            diagnostics.Add(new RirDiagnostic(
                kind: "skipped_overload_set",
                typeName: typeName,
                memberName: ".ctor",
                memberSignature: sig,
                reason: $"overload set — {ctorCandidates.Count} overloads of `.ctor` cannot be uniquely exported to C",
                hint: "Add a C# adapter shim to expose each overload under a distinct name."));
        }

        // --- Properties ---
        var properties = new List<RirProperty>();

        foreach (var handle in typeDef.GetProperties())
        {
            var propDef = mr.GetPropertyDefinition(handle);
            var propName = mr.GetString(propDef.Name);

            var accessors = propDef.GetAccessors();
            // Require at least a public getter.
            if (accessors.Getter.IsNil) continue;

            var getterDef = mr.GetMethodDefinition(accessors.Getter);
            // See the matching mask fix above (methods loop) — Public must be tested as the
            // exact masked value, not as a non-zero AND, or internal/protected getters leak in.
            if ((getterDef.Attributes & System.Reflection.MethodAttributes.MemberAccessMask)
                != System.Reflection.MethodAttributes.Public) continue;

            var (propTypeRef, propDiagnostic) = TryDecodePropertyType(
                mr, propDef, propName, typeName, boundHandleTypeNames, enumTypes, structTypes);
            if (propTypeRef is not null)
            {
                // Same masked-equality fix as the getter check above: a non-zero AND against
                // Public (0x6) also matches Assembly/Family accessors, which would otherwise be
                // (incorrectly) treated as a public setter.
                bool isReadOnly = accessors.Setter.IsNil
                    || (mr.GetMethodDefinition(accessors.Setter).Attributes
                        & System.Reflection.MethodAttributes.MemberAccessMask)
                        != System.Reflection.MethodAttributes.Public;
                bool propIsStatic = (getterDef.Attributes & System.Reflection.MethodAttributes.Static) != 0;

                // ADR-053: a property carries exactly one NullableAttribute (on the Property row
                // itself), falling back to its declaring TypeDef's NullableContextAttribute — "a
                // Property cannot carry a context" (no method tier), hence `methodHandle: default`.
                var finalPropTypeRef = propTypeRef;
                if (NullabilityHelpers.IsNullableCapable(propTypeRef))
                {
                    bool nullable = NullabilityHelpers.Resolve(
                        mr, handle, methodHandle: default, typeHandle, out bool wasOblivious);
                    finalPropTypeRef = NullabilityHelpers.ApplyNullable(propTypeRef, nullable);

                    if (wasOblivious)
                    {
                        diagnostics.Add(new RirDiagnostic(
                            kind: "info_oblivious_nullability",
                            typeName: typeName,
                            memberName: propName,
                            memberSignature: propName,
                            reason: "no NullableAttribute/NullableContextAttribute resolves this " +
                                "property's nullability anywhere in the member -> type fallback chain " +
                                "— the binding assumes non-null.",
                            hint: "Compile the declaring type inside a `#nullable enable` region to " +
                                "make its null-safety explicit."));
                    }
                }

                properties.Add(new RirProperty(propName, finalPropTypeRef, isReadOnly, propIsStatic));
            }
            else if (propDiagnostic is not null)
            {
                diagnostics.Add(propDiagnostic);
            }
        }

        RirType rirType = isInterface
            ? new RirInterface(typeName, methods, properties)
            : new RirClass(typeName, isAbstract && !isInterface, isStatic, methods, properties, constructors);

        return (rirType, diagnostics);
    }

    /// <summary>
    /// Builds a <c>SequenceNumber</c> -&gt; <see cref="ParameterHandle"/> map for a method's
    /// <c>Param</c> rows (ADR-053 prerequisite fix). <see cref="MethodDefinition.GetParameters"/>
    /// returns *all* <c>Param</c> rows, including the return-value pseudo-parameter
    /// (<c>SequenceNumber == 0</c>) whenever the return carries any metadata (e.g. a
    /// <c>[return: Nullable(2)]</c>) — positional indexing into that list silently shifts every
    /// real parameter by one the moment such a row exists. Keying on <c>SequenceNumber</c>
    /// (1-based for real parameters, 0 for the return) is immune to that row's presence.
    /// </summary>
    private static Dictionary<int, ParameterHandle> MapParameterHandlesBySequenceNumber(
        MetadataReader mr, MethodDefinition methodDef)
    {
        var bySeq = new Dictionary<int, ParameterHandle>();
        foreach (var ph in methodDef.GetParameters())
            bySeq[mr.GetParameter(ph).SequenceNumber] = ph;
        return bySeq;
    }

    /// <summary>
    /// Maps a public instance <c>.ctor</c> candidate to a <see cref="RirConstructor"/> (ADR-052).
    /// Only parameters are decoded — a constructor's return is implicit (the enclosing type) and
    /// carries no diagnosable return-type shape, nor any nullability of its own (ADR-053). A
    /// non-bridgeable parameter yields the same per-parameter diagnostic already used for
    /// methods; the constructor is not partially bound.
    /// </summary>
    private static (RirConstructor? Ctor, RirDiagnostic? Diagnostic, RirDiagnostic? ObliviousDiagnostic) TryMapConstructor(
        MetadataReader mr,
        TypeDefinitionHandle typeHandle,
        MethodDefinitionHandle methodHandle,
        string typeName,
        HashSet<string> boundHandleTypeNames,
        IReadOnlyDictionary<string, EnumExtraction> enumTypes,
        IReadOnlyDictionary<string, StructExtraction> structTypes)
    {
        var methodDef = mr.GetMethodDefinition(methodHandle);
        var decoder = new SignatureDecoder(mr, boundHandleTypeNames, enumTypes, structTypes);
        MethodSignature<TypeRefOrDiag> sig;

        try
        {
            sig = methodDef.DecodeSignature(decoder, genericContext: null);
        }
        catch (BadImageFormatException)
        {
            return (null, null, null);
        }

        foreach (var t in sig.ParameterTypes)
        {
            if (t.Diagnostic is not null)
            {
                var fullSig = BuildSignatureString(mr, methodDef, ".ctor");
                return (null, new RirDiagnostic(
                    kind: t.Diagnostic.Kind,
                    typeName: typeName,
                    memberName: ".ctor",
                    memberSignature: fullSig,
                    reason: t.Diagnostic.Reason,
                    hint: t.Diagnostic.Hint), null);
            }
        }

        var parameters = new List<RirParameter>();
        var paramHandlesBySeq = MapParameterHandlesBySequenceNumber(mr, methodDef);
        bool anyOblivious = false;

        for (int i = 0; i < sig.ParameterTypes.Length; i++)
        {
            var paramTypeRef = sig.ParameterTypes[i].TypeRef;
            if (paramTypeRef is null) return (null, null, null); // unknown, non-diagnostic type — skip silently

            int seq = i + 1;
            string paramName = "arg" + i;
            ParameterHandle paramHandle = default;
            if (paramHandlesBySeq.TryGetValue(seq, out var ph))
            {
                paramHandle = ph;
                var pn = mr.GetString(mr.GetParameter(ph).Name);
                if (!string.IsNullOrEmpty(pn)) paramName = pn;
            }

            if (NullabilityHelpers.IsNullableCapable(paramTypeRef))
            {
                bool nullable = NullabilityHelpers.Resolve(mr, paramHandle, methodHandle, typeHandle, out bool oblivious);
                paramTypeRef = NullabilityHelpers.ApplyNullable(paramTypeRef, nullable);
                anyOblivious |= oblivious;
            }

            parameters.Add(new RirParameter(paramName, paramTypeRef));
        }

        RirDiagnostic? obliviousDiagnostic = anyOblivious
            ? new RirDiagnostic(
                kind: "info_oblivious_nullability",
                typeName: typeName,
                memberName: ".ctor",
                memberSignature: BuildSignatureString(mr, methodDef, ".ctor"),
                reason: "no NullableAttribute/NullableContextAttribute resolves one or more of this " +
                    "constructor's reference-typed parameters anywhere in the member -> type fallback " +
                    "chain — the binding assumes non-null.",
                hint: "Compile the declaring type inside a `#nullable enable` region to make its " +
                    "null-safety explicit.")
            : null;

        return (new RirConstructor(parameters), null, obliviousDiagnostic);
    }

    private static (RirMethod? Method, RirDiagnostic? Diagnostic, RirDiagnostic? ObliviousDiagnostic) TryMapMethod(
        MetadataReader mr,
        TypeDefinitionHandle typeHandle,
        MethodDefinitionHandle methodHandle,
        MethodDefinition methodDef,
        string typeName,
        bool isInterface,
        HashSet<string> boundHandleTypeNames,
        IReadOnlyDictionary<string, EnumExtraction> enumTypes,
        IReadOnlyDictionary<string, StructExtraction> structTypes)
    {
        var methodName = mr.GetString(methodDef.Name);
        bool isStatic = (methodDef.Attributes & System.Reflection.MethodAttributes.Static) != 0;

        // Check: default interface method (non-abstract, non-static, has IL body on interface).
        if (isInterface
            && !isStatic
            && (methodDef.Attributes & System.Reflection.MethodAttributes.Abstract) == 0
            && methodDef.RelativeVirtualAddress != 0)
        {
            return (null, new RirDiagnostic(
                kind: "skipped_default_interface_method",
                typeName: typeName,
                memberName: methodName,
                memberSignature: methodName,
                reason: "default interface method — DIMs have no single concrete export site",
                hint: "Override this method in a concrete adapter class."), null);
        }

        var decoder = new SignatureDecoder(mr, boundHandleTypeNames, enumTypes, structTypes);
        MethodSignature<TypeRefOrDiag> sig;

        try
        {
            sig = methodDef.DecodeSignature(decoder, genericContext: null);
        }
        catch (BadImageFormatException)
        {
            return (null, null, null);
        }

        // Check parameters and return type for exclusion rules.
        var allTypes = sig.ParameterTypes.Append(sig.ReturnType).ToList();

        foreach (var t in allTypes)
        {
            if (t.Diagnostic is not null)
            {
                var fullSig = BuildSignatureString(mr, methodDef, methodName);
                return (null, new RirDiagnostic(
                    kind: t.Diagnostic.Kind,
                    typeName: typeName,
                    memberName: methodName,
                    memberSignature: fullSig,
                    reason: t.Diagnostic.Reason,
                    hint: t.Diagnostic.Hint), null);
            }
        }

        // Check async shapes — informational only, not a skip.
        // The return type RirTypeRef would be null/unknown for Task<T> etc.
        // We check the raw type name if decoding produced Unknown.
        var returnTypeRef = sig.ReturnType.TypeRef;
        if (returnTypeRef is null)
        {
            // Unknown type — check for async shapes for info diagnostic.
            var rawReturnName = sig.ReturnType.RawTypeName;
            if (IsAsyncType(rawReturnName))
            {
                var fullSig = BuildSignatureString(mr, methodDef, methodName);
                // Emit info diagnostic but still skip for now (unmapped).
                return (null, new RirDiagnostic(
                    kind: "info_async_not_yet_mapped",
                    typeName: typeName,
                    memberName: methodName,
                    memberSignature: fullSig,
                    reason: $"async return type `{rawReturnName}` is not yet mapped in v1",
                    hint: "This type will be mapped in a future reverse ADR."), null);
            }

            // Unknown non-async type — skip silently (out of v1 scope, no diagnostic defined).
            return (null, null, null);
        }

        var paramHandlesBySeq = MapParameterHandlesBySequenceNumber(mr, methodDef);
        bool anyOblivious = false;

        // ADR-053: the return's nullability lands on the Sequence == 0 Param row (the "return
        // pseudo-parameter"), which only exists when the return's byte differs from whatever its
        // MethodDef/TypeDef NullableContext would otherwise supply. Absent that row, `default`
        // (nil) correctly falls through to the method/type context tiers.
        if (NullabilityHelpers.IsNullableCapable(returnTypeRef))
        {
            ParameterHandle returnParamHandle = paramHandlesBySeq.TryGetValue(0, out var rph) ? rph : default;
            bool nullable = NullabilityHelpers.Resolve(mr, returnParamHandle, methodHandle, typeHandle, out bool oblivious);
            returnTypeRef = NullabilityHelpers.ApplyNullable(returnTypeRef, nullable);
            anyOblivious |= oblivious;
        }

        // Map parameters.
        var parameters = new List<RirParameter>();

        for (int i = 0; i < sig.ParameterTypes.Length; i++)
        {
            var paramTypeRef = sig.ParameterTypes[i].TypeRef;
            if (paramTypeRef is null) return (null, null, null); // should have been caught above

            int seq = i + 1;
            string paramName = "arg" + i;
            ParameterHandle paramHandle = default;
            if (paramHandlesBySeq.TryGetValue(seq, out var ph))
            {
                paramHandle = ph;
                var pn = mr.GetString(mr.GetParameter(ph).Name);
                if (!string.IsNullOrEmpty(pn)) paramName = pn;
            }

            if (NullabilityHelpers.IsNullableCapable(paramTypeRef))
            {
                bool nullable = NullabilityHelpers.Resolve(mr, paramHandle, methodHandle, typeHandle, out bool oblivious);
                paramTypeRef = NullabilityHelpers.ApplyNullable(paramTypeRef, nullable);
                anyOblivious |= oblivious;
            }

            parameters.Add(new RirParameter(paramName, paramTypeRef));
        }

        RirDiagnostic? obliviousDiagnostic = anyOblivious
            ? new RirDiagnostic(
                kind: "info_oblivious_nullability",
                typeName: typeName,
                memberName: methodName,
                memberSignature: BuildSignatureString(mr, methodDef, methodName),
                reason: "no NullableAttribute/NullableContextAttribute resolves this member's return " +
                    "and/or one or more of its reference-typed parameters anywhere in the member -> " +
                    "method -> type fallback chain — the binding assumes non-null.",
                hint: "Compile the declaring type inside a `#nullable enable` region to make its " +
                    "null-safety explicit.")
            : null;

        return (new RirMethod(methodName, returnTypeRef, parameters, isStatic), null, obliviousDiagnostic);
    }

    private static (RirTypeRef? TypeRef, RirDiagnostic? Diagnostic) TryDecodePropertyType(
        MetadataReader mr,
        PropertyDefinition propDef,
        string propName,
        string typeName,
        HashSet<string> boundHandleTypeNames,
        IReadOnlyDictionary<string, EnumExtraction> enumTypes,
        IReadOnlyDictionary<string, StructExtraction> structTypes)
    {
        var decoder = new SignatureDecoder(mr, boundHandleTypeNames, enumTypes, structTypes);
        MethodSignature<TypeRefOrDiag> sig;

        try
        {
            // Properties are encoded as method signatures in ECMA-335; ReturnType is the property type.
            sig = propDef.DecodeSignature(decoder, genericContext: null);
        }
        catch (BadImageFormatException)
        {
            return (null, null);
        }

        var t = sig.ReturnType;
        if (t.Diagnostic is not null)
        {
            return (null, new RirDiagnostic(
                kind: t.Diagnostic.Kind,
                typeName: typeName,
                memberName: propName,
                memberSignature: propName,
                reason: t.Diagnostic.Reason,
                hint: t.Diagnostic.Hint));
        }

        return (t.TypeRef, null);
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private static string GetAssemblyName(MetadataReader mr)
    {
        if (mr.IsAssembly)
        {
            var assemblyDef = mr.GetAssemblyDefinition();
            return mr.GetString(assemblyDef.Name);
        }

        return string.Empty;
    }

    private static bool IsNamespaceIncluded(
        string ns,
        IReadOnlyList<string> includes,
        IReadOnlyList<string> excludes)
    {
        if (excludes.Count > 0 && excludes.Any(e => ns == e || ns.StartsWith(e + ".", StringComparison.Ordinal)))
            return false;

        if (includes.Count == 0) return true;
        return includes.Any(inc => ns == inc || ns.StartsWith(inc + ".", StringComparison.Ordinal));
    }

    private static string DescribeFirstParams(MetadataReader mr, MethodDefinition method)
    {
        try
        {
            // No bound-handle types needed here — purely for human-readable diagnostic strings.
            var decoder = new SignatureDecoder(mr);
            var sig = method.DecodeSignature(decoder, genericContext: null);
            return string.Join(", ", sig.ParameterTypes.Select(p => p.RawTypeName ?? "?"));
        }
        catch
        {
            return "?";
        }
    }

    private static string BuildSignatureString(MetadataReader mr, MethodDefinition method, string name)
    {
        try
        {
            // No bound-handle types needed here — purely for human-readable diagnostic strings.
            var decoder = new SignatureDecoder(mr);
            var sig = method.DecodeSignature(decoder, genericContext: null);
            var paramStr = string.Join(", ", sig.ParameterTypes.Select(p => p.RawTypeName ?? "?"));
            return $"{name}({paramStr})";
        }
        catch
        {
            return name;
        }
    }

    private static bool IsAsyncType(string? typeName)
    {
        if (typeName is null) return false;
        return AsyncTypeNames.Any(a => typeName == a || typeName.StartsWith(a, StringComparison.Ordinal));
    }
}

// ---------------------------------------------------------------------------
// Shared metadata helpers
// ---------------------------------------------------------------------------

internal static class MetadataHelpers
{
    internal static bool IsEnum(MetadataReader mr, TypeDefinition typeDef)
    {
        if (typeDef.BaseType.Kind != HandleKind.TypeReference) return false;

        var baseRef = mr.GetTypeReference((TypeReferenceHandle)typeDef.BaseType);
        return mr.GetString(baseRef.Namespace) == "System" && mr.GetString(baseRef.Name) == "Enum";
    }

    /// <summary>
    /// Returns true if <paramref name="typeDef"/> carries <c>IsByRefLikeAttribute</c>,
    /// which marks it as a <c>ref struct</c> that cannot cross the C ABI.
    /// </summary>
    internal static bool IsRefStructType(MetadataReader mr, TypeDefinition typeDef)
    {
        foreach (var handle in typeDef.GetCustomAttributes())
        {
            var attr = mr.GetCustomAttribute(handle);
            var attrTypeName = GetCustomAttributeTypeName(mr, attr);
            if (attrTypeName == "System.Runtime.CompilerServices.IsByRefLikeAttribute")
                return true;
        }

        return false;
    }

    /// <summary>
    /// Returns true if <paramref name="typeDef"/> is a value type (struct or enum): its base type
    /// is <c>System.ValueType</c> or <c>System.Enum</c>. Value types cannot be bound as opaque
    /// GCHandle types (ADR-051).
    /// </summary>
    internal static bool IsValueType(MetadataReader mr, TypeDefinition typeDef)
    {
        if (typeDef.BaseType.IsNil) return false;

        if (typeDef.BaseType.Kind == HandleKind.TypeReference)
        {
            var baseRef = mr.GetTypeReference((TypeReferenceHandle)typeDef.BaseType);
            var ns = mr.GetString(baseRef.Namespace);
            var name = mr.GetString(baseRef.Name);
            return ns == "System" && (name == "ValueType" || name == "Enum");
        }

        return false;
    }

    internal static string? GetCustomAttributeTypeName(MetadataReader mr, CustomAttribute attr)
    {
        if (attr.Constructor.Kind == HandleKind.MemberReference)
        {
            var ctor = mr.GetMemberReference((MemberReferenceHandle)attr.Constructor);
            if (ctor.Parent.Kind == HandleKind.TypeReference)
            {
                var typeRef = mr.GetTypeReference((TypeReferenceHandle)ctor.Parent);
                var ns = mr.GetString(typeRef.Namespace);
                var name = mr.GetString(typeRef.Name);
                return string.IsNullOrEmpty(ns) ? name : $"{ns}.{name}";
            }
        }
        else if (attr.Constructor.Kind == HandleKind.MethodDefinition)
        {
            var ctor = mr.GetMethodDefinition((MethodDefinitionHandle)attr.Constructor);
            var typeDef = mr.GetTypeDefinition(ctor.GetDeclaringType());
            var ns = mr.GetString(typeDef.Namespace);
            var name = mr.GetString(typeDef.Name);
            return string.IsNullOrEmpty(ns) ? name : $"{ns}.{name}";
        }

        return null;
    }
}

// ---------------------------------------------------------------------------
// ADR-053: NullableAttribute / NullableContextAttribute decoding
// ---------------------------------------------------------------------------

/// <summary>
/// Decodes <c>System.Runtime.CompilerServices.NullableAttribute</c> and
/// <c>NullableContextAttribute</c> (ADR-053) and resolves the member -> method -> type -> oblivious
/// fallback chain the ADR specifies. The ADR's premise that both attributes are always
/// compiler-synthesized *into the assembly being compiled* (constructor = <see cref="MethodDefinitionHandle"/>)
/// only holds for target frameworks that don't already ship the types in the BCL (pre-.NET 5-ish,
/// or netstandard2.0). On a modern TFM (this fixture targets net8.0) the BCL already defines both
/// attributes, so Roslyn instead emits a <see cref="MemberReferenceHandle"/> to the existing type —
/// confirmed empirically against the <c>SampleDependency</c> fixture DLL. Both shapes are handled:
/// <see cref="MetadataHelpers.GetCustomAttributeTypeName"/> already resolves the type name for
/// either kind, and the ctor-signature decode below (<see cref="CustomAttributeCtorTakesByteArray"/>)
/// branches on <see cref="EntityHandle.Kind"/> to decode either shape's constructor signature.
/// </summary>
internal static class NullabilityHelpers
{
    private const string NullableAttributeFullName = "System.Runtime.CompilerServices.NullableAttribute";
    private const string NullableContextAttributeFullName = "System.Runtime.CompilerServices.NullableContextAttribute";

    /// <summary>
    /// True if <paramref name="mr"/> carries a <c>NullableAttribute</c> or
    /// <c>NullableContextAttribute</c> anywhere at all (any table, any row) — the signal for
    /// "whole assembly is nullable-oblivious" (a legacy, pre-C#-8, or
    /// <c>&lt;Nullable&gt;disable&lt;/Nullable&gt;</c> assembly). A single oblivious *island*
    /// inside an otherwise-annotated assembly (e.g. a `#nullable disable` region) does not trip
    /// this: that island's own `TypeDef`/`MethodDef` rows carry no nullable attributes, but
    /// plenty of other rows in the same module do.
    /// </summary>
    public static bool AssemblyHasAnyNullableAnnotation(MetadataReader mr)
    {
        foreach (var handle in mr.CustomAttributes)
        {
            var attr = mr.GetCustomAttribute(handle);
            var name = MetadataHelpers.GetCustomAttributeTypeName(mr, attr);
            if (name == NullableAttributeFullName || name == NullableContextAttributeFullName)
                return true;
        }

        return false;
    }

    /// <summary>
    /// Resolves whether a single reference-typed slot (a parameter, the return-value
    /// pseudo-parameter, or a property) is nullable, per the ADR-053 fallback chain:
    /// <c>memberHandle</c>'s own <c>NullableAttribute</c> -&gt; <c>methodHandle</c>'s
    /// <c>NullableContextAttribute</c> -&gt; <c>typeHandle</c>'s <c>NullableContextAttribute</c>
    /// -&gt; oblivious (byte 0). Pass <c>default</c> for <paramref name="memberHandle"/> when the
    /// slot has no metadata row of its own (e.g. a return value whose method has no
    /// `Sequence == 0` `Param` row), and for <paramref name="methodHandle"/> when the chain has no
    /// method tier (a property "cannot carry a context", ADR-053).
    /// </summary>
    public static bool Resolve(
        MetadataReader mr,
        EntityHandle memberHandle,
        EntityHandle methodHandle,
        EntityHandle typeHandle,
        out bool wasOblivious)
    {
        byte? resolved = memberHandle.IsNil ? null : GetNullableAttributeByte(mr, memberHandle);
        if (resolved is null && !methodHandle.IsNil) resolved = GetNullableContextByte(mr, methodHandle);
        if (resolved is null) resolved = GetNullableContextByte(mr, typeHandle);

        byte b = resolved ?? 0;
        wasOblivious = b == 0;
        return b == 2;
    }

    private static byte? GetNullableAttributeByte(MetadataReader mr, EntityHandle handle)
    {
        var attr = FindCustomAttribute(mr, handle, NullableAttributeFullName);
        return attr is CustomAttribute found ? DecodeNullableAttributeByte(mr, found) : null;
    }

    private static byte? GetNullableContextByte(MetadataReader mr, EntityHandle handle)
    {
        var attr = FindCustomAttribute(mr, handle, NullableContextAttributeFullName);
        if (attr is not CustomAttribute found) return null;

        // NullableContextAttribute always takes a single `byte` (never the byte[] form).
        var reader = mr.GetBlobReader(found.Value);
        if (reader.ReadUInt16() != 1) return null; // malformed prolog — be defensive, not crashy
        return reader.ReadByte();
    }

    private static CustomAttribute? FindCustomAttribute(MetadataReader mr, EntityHandle handle, string fullName)
    {
        foreach (var caHandle in mr.GetCustomAttributes(handle))
        {
            var attr = mr.GetCustomAttribute(caHandle);
            if (MetadataHelpers.GetCustomAttributeTypeName(mr, attr) == fullName)
                return attr;
        }

        return null;
    }

    /// <summary>
    /// Decodes a <c>NullableAttribute</c>'s payload: either a single <c>byte</c> or a
    /// <c>byte[]</c> ("used when all values in the byte[] are the same" per the Roslyn spec) — the
    /// v1 bridgeable subset only ever produces depth-1 type trees, so the array form (when it
    /// appears at all) is always uniform and index 0 is always representative.
    /// </summary>
    private static byte? DecodeNullableAttributeByte(MetadataReader mr, CustomAttribute attr)
    {
        var isByteArray = CustomAttributeCtorTakesByteArray(mr, attr.Constructor);
        var reader = mr.GetBlobReader(attr.Value);
        if (reader.ReadUInt16() != 1) return null; // malformed prolog

        if (!isByteArray) return reader.ReadByte();

        // SZArray fixed-arg encoding: a 4-byte element count (0xFFFFFFFF for a null array),
        // then that many elements. Take index 0; a null or empty array carries no usable value.
        int count = reader.ReadInt32();
        return count > 0 ? reader.ReadByte() : null;
    }

    /// <summary>
    /// Decodes the constructor's own signature to tell the single-<c>byte</c> overload apart from
    /// the <c>byte[]</c> overload. The constructor handle is a <see cref="MethodDefinitionHandle"/>
    /// when the attribute type is compiler-synthesized into the compiled assembly (legacy TFMs),
    /// or a <see cref="MemberReferenceHandle"/> when it resolves to the BCL-provided type (modern
    /// TFMs, e.g. net8.0) — both are decoded via <see cref="SignatureDecoder"/>, which already maps
    /// <c>byte</c> to <c>RirPrimitiveType("byte")</c> and an SZArray of it to raw name "byte[]".
    /// </summary>
    private static bool CustomAttributeCtorTakesByteArray(MetadataReader mr, EntityHandle ctorHandle)
    {
        try
        {
            var decoder = new SignatureDecoder(mr);
            ImmutableArray<TypeRefOrDiag> parameterTypes = ctorHandle.Kind switch
            {
                HandleKind.MethodDefinition => mr.GetMethodDefinition((MethodDefinitionHandle)ctorHandle)
                    .DecodeSignature(decoder, genericContext: null).ParameterTypes,
                HandleKind.MemberReference => mr.GetMemberReference((MemberReferenceHandle)ctorHandle)
                    .DecodeMethodSignature(decoder, genericContext: null).ParameterTypes,
                _ => ImmutableArray<TypeRefOrDiag>.Empty,
            };

            return parameterTypes.Length == 1 && parameterTypes[0].RawTypeName == "byte[]";
        }
        catch (BadImageFormatException)
        {
            return false;
        }
    }

    /// <summary>True for the two reference-typed <see cref="RirTypeRef"/> shapes that carry a
    /// <c>nullable</c> flag (ADR-053 2a): <see cref="RirStringType"/> and
    /// <see cref="RirObjectHandleType"/>. Value types (<see cref="RirPrimitiveType"/>,
    /// <see cref="RirEnumType"/>) and <see cref="RirVoidType"/> are unaffected — a nullable value
    /// type is <c>System.Nullable&lt;T&gt;</c>, a distinct closed generic struct, deferred.</summary>
    public static bool IsNullableCapable(RirTypeRef typeRef) => typeRef is RirStringType or RirObjectHandleType;

    public static RirTypeRef ApplyNullable(RirTypeRef typeRef, bool nullable) => typeRef switch
    {
        RirStringType => new RirStringType(nullable),
        RirObjectHandleType h => new RirObjectHandleType(h.Namespace, h.Name, nullable),
        _ => typeRef,
    };
}

// ---------------------------------------------------------------------------
// Signature decoder
// ---------------------------------------------------------------------------

/// <summary>
/// Either a successfully mapped RirTypeRef, a diagnostic describing why the type cannot cross the
/// bridge, or (for unknown non-async types) null TypeRef with null Diagnostic (silently skipped).
/// </summary>
internal sealed record TypeRefOrDiag(
    RirTypeRef? TypeRef,
    PendingDiagnostic? Diagnostic,
    string? RawTypeName);

internal sealed record PendingDiagnostic(string Kind, string Reason, string Hint);

internal sealed record EnumExtraction(RirEnum? Enum, string? Reason)
{
    internal static EnumExtraction Supported(RirEnum value) => new(value, null);
    internal static EnumExtraction Unsupported(string reason) => new(null, reason);
}

/// <summary>ADR-056: the outcome of applying the Decision 3a "Shape A" rules to one value type
/// (struct) candidate. Mirrors <see cref="EnumExtraction"/>.</summary>
internal sealed record StructExtraction(RirStruct? Struct, string? Reason)
{
    internal static StructExtraction Supported(RirStruct value) => new(value, null);
    internal static StructExtraction Unsupported(string reason) => new(null, reason);
}

/// <summary>
/// Implements <see cref="ISignatureTypeProvider{TType,TGenericContext}"/> using
/// <see cref="System.Reflection.Metadata"/> primitives. Maps the v1 type vocabulary to
/// <see cref="TypeRefOrDiag"/>; everything else carries a <see cref="PendingDiagnostic"/>.
/// </summary>
internal sealed class SignatureDecoder : ISignatureTypeProvider<TypeRefOrDiag, object?>
{
    private const string DynamicAttributeFullName = "System.Runtime.CompilerServices.DynamicAttribute";

    private readonly MetadataReader _mr;

    /// <summary>
    /// The set of fully-qualified type names eligible to cross the bridge as opaque GCHandle
    /// pointers (ADR-051). Built by <c>AssemblyExtractor.CollectBoundHandleTypeNames</c>.
    /// Pass null (or omit) for display-only decoders that only produce raw type name strings.
    /// </summary>
    private readonly HashSet<string> _boundHandleTypeNames;
    private readonly IReadOnlyDictionary<string, EnumExtraction> _enumTypes;
    /// <summary>
    /// ADR-056: struct candidates by fully-qualified name. Pass null (or omit) when decoding
    /// struct COMPONENT types themselves (<c>AssemblyExtractor.ExtractStruct</c>) — an empty map
    /// there makes a nested-struct component always fall through to "not bridgeable", which is the
    /// intended v1 behaviour ("nested struct components deferred").
    /// </summary>
    private readonly IReadOnlyDictionary<string, StructExtraction> _structTypes;

    public SignatureDecoder(
        MetadataReader mr,
        HashSet<string>? boundHandleTypeNames = null,
        IReadOnlyDictionary<string, EnumExtraction>? enumTypes = null,
        IReadOnlyDictionary<string, StructExtraction>? structTypes = null)
    {
        _mr = mr;
        _boundHandleTypeNames = boundHandleTypeNames
            ?? new HashSet<string>(StringComparer.Ordinal);
        _enumTypes = enumTypes ?? new Dictionary<string, EnumExtraction>(StringComparer.Ordinal);
        _structTypes = structTypes ?? new Dictionary<string, StructExtraction>(StringComparer.Ordinal);
    }

    // Primitives
    public TypeRefOrDiag GetPrimitiveType(PrimitiveTypeCode typeCode)
    {
        var (typeRef, name) = typeCode switch
        {
            PrimitiveTypeCode.Void    => ((RirTypeRef)RirVoidType.Instance, "void"),
            PrimitiveTypeCode.Boolean => (new RirPrimitiveType("bool"), "bool"),
            PrimitiveTypeCode.Byte    => (new RirPrimitiveType("byte"), "byte"),
            PrimitiveTypeCode.Int16   => (new RirPrimitiveType("short"), "short"),
            PrimitiveTypeCode.Int32   => (new RirPrimitiveType("int"), "int"),
            PrimitiveTypeCode.Int64   => (new RirPrimitiveType("long"), "long"),
            PrimitiveTypeCode.Single  => (new RirPrimitiveType("float"), "float"),
            PrimitiveTypeCode.Double  => (new RirPrimitiveType("double"), "double"),
            PrimitiveTypeCode.Char    => (new RirPrimitiveType("char"), "char"),
            PrimitiveTypeCode.String  => (new RirStringType(), "string"),
            PrimitiveTypeCode.Object  => (null!, "object"),
            _                         => (null!, typeCode.ToString()),
        };

        if (typeRef is null)
        {
            // object / unrecognised primitive — not in v1 vocabulary, not a structural skip either.
            return new TypeRefOrDiag(null, null, name);
        }

        return new TypeRefOrDiag(typeRef, null, name);
    }

    // TypeDef — type defined in the same assembly.
    public TypeRefOrDiag GetTypeFromDefinition(MetadataReader mr, TypeDefinitionHandle handle, byte rawTypeKind)
    {
        var typeDef = mr.GetTypeDefinition(handle);
        var name = mr.GetString(typeDef.Name);
        var ns = mr.GetString(typeDef.Namespace);
        var fullName = string.IsNullOrEmpty(ns) ? name : $"{ns}.{name}";

        // ref structs cannot cross the C ABI — diagnose before the handle check (ADR-043).
        if (MetadataHelpers.IsRefStructType(mr, typeDef))
        {
            return new TypeRefOrDiag(null,
                new PendingDiagnostic(
                    "skipped_ref_struct",
                    $"ref struct parameter — `{fullName}` is stack-only (IsByRefLike) and cannot cross the C ABI",
                    "Expose this method in a C# adapter shim with a different parameter type."),
                fullName);
        }

        if (_enumTypes.TryGetValue(fullName, out var enumType))
        {
            if (enumType.Enum is not null)
                return new TypeRefOrDiag(new RirEnumType(ns, name), null, fullName);

            return new TypeRefOrDiag(null,
                new PendingDiagnostic(
                    "skipped_unsupported_enum",
                    $"enum `{fullName}` is not bridgeable: {enumType.Reason}",
                    "Use a non-[Flags] default-int enum with unique contiguous values from 0 through N-1."),
                fullName);
        }

        if (MetadataHelpers.IsEnum(mr, typeDef))
        {
            return new TypeRefOrDiag(null,
                new PendingDiagnostic(
                    "skipped_unsupported_enum",
                    $"enum `{fullName}` is not part of the bound extraction set",
                    "Bind the enum namespace and use a supported ordinal enum shape."),
                fullName);
        }

        // ADR-056: a struct candidate crosses the bridge as a decomposed RirStructType (never an
        // opaque handle — checked before _boundHandleTypeNames below), or is diagnosed as
        // unsupported, mirroring exactly what the enum branch above already does.
        if (_structTypes.TryGetValue(fullName, out var structType))
        {
            if (structType.Struct is not null)
                return new TypeRefOrDiag(new RirStructType(ns, name), null, fullName);

            return new TypeRefOrDiag(null,
                new PendingDiagnostic(
                    "skipped_unsupported_struct",
                    $"struct `{fullName}` is not bridgeable: {structType.Reason}",
                    "See ADR-056 Decision 3a for the bridgeable struct shape."),
                fullName);
        }

        // If the type is a bound, wrapper-eligible class in this extraction run, it crosses the
        // bridge as an opaque GCHandle pointer (ADR-051 RirObjectHandleType).
        if (_boundHandleTypeNames.Contains(fullName))
            return new TypeRefOrDiag(new RirObjectHandleType(ns, name), null, fullName);

        // Type is in the same assembly but is NOT a bound handle type (excluded namespace,
        // interface, static class, value type, or ref struct already handled above). Members
        // that reference it are skipped with a diagnostic (ADR-051; ADR-043 fail-fast contract).
        return new TypeRefOrDiag(null,
            new PendingDiagnostic(
                "skipped_unbound_type_reference",
                $"type `{fullName}` is not a bridgeable bound class in this extraction run",
                "Bind this type's namespace to make it a handle type, or expose an equivalent static API."),
            fullName);
    }

    // TypeRef — type defined in another assembly.
    public TypeRefOrDiag GetTypeFromReference(MetadataReader mr, TypeReferenceHandle handle, byte rawTypeKind)
    {
        var typeRef = mr.GetTypeReference(handle);
        var name = mr.GetString(typeRef.Name);
        var ns = mr.GetString(typeRef.Namespace);
        var fullName = string.IsNullOrEmpty(ns) ? name : $"{ns}.{name}";

        // System.String is the only external reference type in the v1 vocabulary.
        if (fullName == "System.String")
            return new TypeRefOrDiag(new RirStringType(), null, "string");

        // All other external type references are outside the bound set and cannot be opaque
        // handles. Emit a diagnostic so the member skip is visible (ADR-051; replaces the
        // previous silent null,null for direct non-generic external type refs).
        // Note: for generic instantiations (Task<T>, List<T>, etc.) GetTypeFromReference is
        // called on the open generic base type, but GetGenericInstantiation deliberately drops
        // the diagnostic and returns null,null — those methods are still silently skipped, which
        // is the correct v1 behaviour for complex types.
        return new TypeRefOrDiag(null,
            new PendingDiagnostic(
                "skipped_unbound_type_reference",
                $"type `{fullName}` is defined outside the bound assemblies and cannot be an opaque handle in this extraction run",
                "Include this type's assembly in the extraction run to bind it, or expose an equivalent static API."),
            fullName);
    }

    // TypeSpec — instantiated or modified types (generic instantiations, arrays, etc.).
    public TypeRefOrDiag GetTypeFromSpecification(
        MetadataReader mr, object? genericContext, TypeSpecificationHandle handle, byte rawTypeKind)
    {
        // TypeSpec covers generic instantiations like Task<T>, List<string>, etc.
        // We let the decoder recurse — the result comes back via GetGenericInstantiation.
        var spec = mr.GetTypeSpecification(handle);
        return spec.DecodeSignature(this, genericContext);
    }

    // Generic instantiation e.g. Task<int>, IEnumerable<string>.
    public TypeRefOrDiag GetGenericInstantiation(TypeRefOrDiag genericType, ImmutableArray<TypeRefOrDiag> typeArguments)
    {
        var rawName = genericType.RawTypeName;

        // Async shapes — informational (not a structural skip).
        if (IsAsyncTypeName(rawName))
            return new TypeRefOrDiag(null, null, rawName);

        // All other generic instantiations are not in the v1 vocabulary.
        // The diagnostic from GetTypeFromReference on the open base type is deliberately dropped:
        // a skipped_unbound_type_reference on List<string> would be noise with no actionable fix.
        return new TypeRefOrDiag(null, null, rawName ?? "?");
    }

    // Open generic type parameter (T, TResult, etc.).
    public TypeRefOrDiag GetGenericTypeParameter(object? genericContext, int index)
    {
        return new TypeRefOrDiag(null,
            new PendingDiagnostic(
                "skipped_open_generic",
                "open generic type parameter in signature — no concrete type at code-generation time",
                "Expose a concrete overload in a C# adapter shim."),
            $"T{index}");
    }

    public TypeRefOrDiag GetGenericMethodParameter(object? genericContext, int index)
    {
        return new TypeRefOrDiag(null,
            new PendingDiagnostic(
                "skipped_open_generic",
                "open generic method parameter in signature — no concrete type at code-generation time",
                "Expose a concrete overload in a C# adapter shim."),
            $"M{index}");
    }

    // Modified types (e.g. volatile, required/optional modifiers).
    public TypeRefOrDiag GetModifiedType(TypeRefOrDiag modifier, TypeRefOrDiag unmodifiedType, bool isRequired)
    {
        // DynamicAttribute manifests as a required/optional modifier in some scenarios,
        // but more commonly it appears as a custom attribute. Check modifier name.
        if (modifier.RawTypeName == DynamicAttributeFullName)
        {
            return new TypeRefOrDiag(null,
                new PendingDiagnostic(
                    "skipped_dynamic",
                    "dynamic type — DLR late-binding cannot be represented in a static C ABI signature",
                    "Replace the dynamic parameter with a concrete type in a C# adapter shim."),
                "dynamic");
        }

        return unmodifiedType;
    }

    // Pointer types — not in v1 vocabulary.
    public TypeRefOrDiag GetPointerType(TypeRefOrDiag elementType) =>
        new TypeRefOrDiag(null, null, elementType.RawTypeName + "*");

    // ByReference types (ref T) — not in v1 vocabulary.
    public TypeRefOrDiag GetByReferenceType(TypeRefOrDiag elementType) =>
        new TypeRefOrDiag(null, null, "ref " + elementType.RawTypeName);

    // Arrays — not in v1 vocabulary.
    public TypeRefOrDiag GetArrayType(TypeRefOrDiag elementType, ArrayShape shape) =>
        new TypeRefOrDiag(null, null, elementType.RawTypeName + "[]");

    public TypeRefOrDiag GetSZArrayType(TypeRefOrDiag elementType) =>
        new TypeRefOrDiag(null, null, elementType.RawTypeName + "[]");

    // FunctionPointer — not in v1 vocabulary.
    public TypeRefOrDiag GetFunctionPointerType(MethodSignature<TypeRefOrDiag> signature) =>
        new TypeRefOrDiag(null, null, "delegate*");

    // Pinned — pass through.
    public TypeRefOrDiag GetPinnedType(TypeRefOrDiag elementType) => elementType;

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    private static bool IsAsyncTypeName(string? name)
    {
        if (name is null) return false;
        return name is "System.Threading.Tasks.Task"
            or "System.Threading.Tasks.Task`1"
            or "System.Threading.Tasks.ValueTask"
            or "System.Threading.Tasks.ValueTask`1"
            or "System.Collections.Generic.IAsyncEnumerable`1";
    }
}

// ---------------------------------------------------------------------------
// RIR model (mirrors RirModel.kt exactly — field names and "kind" values)
// ---------------------------------------------------------------------------

[JsonPolymorphic(TypeDiscriminatorPropertyName = "kind")]
[JsonDerivedType(typeof(RirClass), "class")]
[JsonDerivedType(typeof(RirInterface), "interface")]
[JsonDerivedType(typeof(RirEnum), "enum")]
[JsonDerivedType(typeof(RirStruct), "struct")]
internal abstract class RirType
{
    public abstract string Name { get; }
}

internal sealed class RirClass : RirType
{
    public RirClass(
        string name,
        bool isAbstract,
        bool isStatic,
        IReadOnlyList<RirMethod> methods,
        IReadOnlyList<RirProperty> properties,
        IReadOnlyList<RirConstructor>? constructors = null)
    {
        Name = name;
        IsAbstract = isAbstract;
        IsStatic = isStatic;
        Methods = methods;
        Properties = properties;
        Constructors = constructors ?? Array.Empty<RirConstructor>();
    }

    public override string Name { get; }
    public bool IsAbstract { get; }
    /// <summary>True when the C# class is <c>abstract sealed</c> (static) in ECMA-335 (ADR-051).</summary>
    public bool IsStatic { get; }
    public IReadOnlyList<RirMethod> Methods { get; }
    public IReadOnlyList<RirProperty> Properties { get; }
    /// <summary>
    /// At most one public instance <c>.ctor</c> per type in v1 (ADR-052). Empty for static
    /// classes, interfaces, abstract classes, and classes with no public instance constructor or
    /// with more than one (an overload set, skipped + diagnosed instead).
    /// </summary>
    public IReadOnlyList<RirConstructor> Constructors { get; }
}

internal sealed class RirInterface : RirType
{
    public RirInterface(string name, IReadOnlyList<RirMethod> methods, IReadOnlyList<RirProperty> properties)
    {
        Name = name;
        Methods = methods;
        Properties = properties;
    }

    public override string Name { get; }
    public IReadOnlyList<RirMethod> Methods { get; }
    public IReadOnlyList<RirProperty> Properties { get; }
}

internal sealed class RirEnum : RirType
{
    public RirEnum(string name, IReadOnlyList<RirEnumEntry> entries)
    {
        Name = name;
        Entries = entries;
    }

    public override string Name { get; }
    public IReadOnlyList<RirEnumEntry> Entries { get; }
}

internal sealed class RirEnumEntry
{
    public RirEnumEntry(string name, int ordinal)
    {
        Name = name;
        Ordinal = ordinal;
    }

    public string Name { get; }
    public int Ordinal { get; }
}

/// <summary>
/// ADR-056: a C# struct that satisfies the Decision 3a "Shape A" rules. Mirrors
/// <c>RirStruct</c> in <c>RirModel.kt</c> field-for-field. Struct members (methods, computed
/// properties) are deliberately not enumerated in v1 — <see cref="Components"/> is the complete
/// wire contract, derived from the struct's single public instance constructor.
/// </summary>
internal sealed class RirStruct : RirType
{
    public RirStruct(string name, IReadOnlyList<RirStructComponent> components)
    {
        Name = name;
        Components = components;
    }

    public override string Name { get; }
    public IReadOnlyList<RirStructComponent> Components { get; }
}

/// <summary>
/// One component of a bridgeable struct (ADR-056). <see cref="Name"/> is the constructor
/// parameter name (drives the Kotlin property name); <see cref="ReadName"/> is the public
/// property used to read the value back, which may differ in case from <see cref="Name"/>
/// (verified: ctor `x` vs. property `X`). Mirrors <c>RirStructComponent</c> in
/// <c>RirModel.kt</c> field-for-field.
/// </summary>
internal sealed class RirStructComponent
{
    public RirStructComponent(string name, string readName, RirTypeRef type)
    {
        Name = name;
        ReadName = readName;
        Type = type;
    }

    public string Name { get; }
    public string ReadName { get; }
    public RirTypeRef Type { get; }
}

internal sealed class RirMethod
{
    public RirMethod(string name, RirTypeRef returnType, IReadOnlyList<RirParameter> parameters, bool isStatic)
    {
        Name = name;
        ReturnType = returnType;
        Parameters = parameters;
        IsStatic = isStatic;
    }

    public string Name { get; }
    public RirTypeRef ReturnType { get; }
    public IReadOnlyList<RirParameter> Parameters { get; }
    public bool IsStatic { get; }
}

internal sealed class RirProperty
{
    public RirProperty(string name, RirTypeRef type, bool isReadOnly, bool isStatic)
    {
        Name = name;
        Type = type;
        IsReadOnly = isReadOnly;
        IsStatic = isStatic;
    }

    public string Name { get; }
    public RirTypeRef Type { get; }
    public bool IsReadOnly { get; }
    public bool IsStatic { get; }
}

internal sealed class RirParameter
{
    public RirParameter(string name, RirTypeRef type)
    {
        Name = name;
        Type = type;
    }

    public string Name { get; }
    public RirTypeRef Type { get; }
}

/// <summary>
/// A C# public instance constructor (ADR-052). Return is implicit — the enclosing
/// <see cref="RirClass"/>'s own <c>RirObjectHandleType</c> — so this is a distinct node rather
/// than reusing <see cref="RirMethod"/>, whose <c>ReturnType</c> is mandatory. Mirrors
/// <c>RirConstructor(parameters)</c> in <c>RirModel.kt</c> field-for-field.
/// </summary>
internal sealed class RirConstructor
{
    public RirConstructor(IReadOnlyList<RirParameter> parameters)
    {
        Parameters = parameters;
    }

    public IReadOnlyList<RirParameter> Parameters { get; }
}

[JsonPolymorphic(TypeDiscriminatorPropertyName = "kind")]
[JsonDerivedType(typeof(RirVoidType), "void")]
[JsonDerivedType(typeof(RirStringType), "string")]
[JsonDerivedType(typeof(RirPrimitiveType), "primitive")]
[JsonDerivedType(typeof(RirObjectHandleType), "handle")]
[JsonDerivedType(typeof(RirEnumType), "enum")]
[JsonDerivedType(typeof(RirStructType), "struct")]
internal abstract class RirTypeRef { }

internal sealed class RirVoidType : RirTypeRef
{
    public static readonly RirVoidType Instance = new();
    private RirVoidType() { }
}

/// <summary>
/// Mirrors <c>RirStringType</c> in <c>RirModel.kt</c> field-for-field (ADR-053): <c>nullable</c>
/// reflects the C# <c>NullableAttribute</c>/<c>NullableContextAttribute</c> fallback chain
/// resolved for the specific slot (parameter, return, or property) this instance was built for.
/// The no-longer-a-singleton shape is deliberate: two <c>string</c> slots in the same signature
/// can have different nullability (e.g. <c>string Greet(string? name)</c>).
/// </summary>
internal sealed class RirStringType : RirTypeRef
{
    public RirStringType(bool nullable = false) => Nullable = nullable;
    public bool Nullable { get; }
}

internal sealed class RirPrimitiveType : RirTypeRef
{
    public RirPrimitiveType(string name) => Name = name;
    public string Name { get; }
}

/// <summary>
/// A reference to a bound C# class that crosses the bridge as an opaque
/// <see cref="System.Runtime.InteropServices.GCHandle"/> pointer (ADR-051).
/// Mirrors <c>RirObjectHandleType</c> in <c>RirModel.kt</c> field-for-field:
/// <c>namespace</c> and <c>name</c> (camelCase via the shared JSON context policy).
/// </summary>
internal sealed class RirObjectHandleType : RirTypeRef
{
    public RirObjectHandleType(string @namespace, string name, bool nullable = false)
    {
        Namespace = @namespace;
        Name = name;
        Nullable = nullable;
    }

    /// <summary>The C# namespace of the referenced type, e.g. <c>"Sample.Text"</c>.</summary>
    public string Namespace { get; }

    /// <summary>The simple type name, e.g. <c>"Template"</c>.</summary>
    public string Name { get; }

    /// <summary>ADR-053: resolved from the member's own <c>NullableAttribute</c>, falling back to
    /// the enclosing method's/type's <c>NullableContextAttribute</c>, defaulting to <c>false</c>
    /// (non-null) when neither is present anywhere in the chain (oblivious).</summary>
    public bool Nullable { get; }
}

internal sealed class RirEnumType : RirTypeRef
{
    public RirEnumType(string @namespace, string name)
    {
        Namespace = @namespace;
        Name = name;
    }

    public string Namespace { get; }
    public string Name { get; }
}

/// <summary>
/// A reference to a bridgeable struct (ADR-056). Unlike <see cref="RirObjectHandleType"/>, this
/// never crosses the bridge as a pointer — the two generators expand it into its
/// <see cref="RirStruct.Components"/> at the ABI level (arguments in, out-pointers out). Carries
/// no <c>nullable</c> flag by design (ADR-056): a nullable value type is
/// <c>System.Nullable&lt;T&gt;</c>, a distinct closed generic struct, not an annotation on this
/// type ref. Mirrors <c>RirStructType</c> in <c>RirModel.kt</c> field-for-field.
/// </summary>
internal sealed class RirStructType : RirTypeRef
{
    public RirStructType(string @namespace, string name)
    {
        Namespace = @namespace;
        Name = name;
    }

    public string Namespace { get; }
    public string Name { get; }
}

internal sealed class RirNamespace
{
    public RirNamespace(string name, IReadOnlyList<RirType> types)
    {
        Name = name;
        Types = types;
    }

    public string Name { get; }
    public IReadOnlyList<RirType> Types { get; }
}

internal sealed class RirAssembly
{
    public RirAssembly(
        string packageId,
        string assemblyName,
        IReadOnlyList<RirNamespace> namespaces,
        IReadOnlyList<RirDiagnostic> diagnostics)
    {
        PackageId = packageId;
        AssemblyName = assemblyName;
        Namespaces = namespaces;
        Diagnostics = diagnostics;
    }

    public string PackageId { get; }
    public string AssemblyName { get; }
    public IReadOnlyList<RirNamespace> Namespaces { get; }
    public IReadOnlyList<RirDiagnostic> Diagnostics { get; }
}

internal sealed class RirFile
{
    public RirFile(IReadOnlyList<RirAssembly> assemblies) => Assemblies = assemblies;
    public IReadOnlyList<RirAssembly> Assemblies { get; }
}

internal sealed class RirDiagnostic
{
    public RirDiagnostic(
        string kind,
        string typeName,
        string memberName,
        string memberSignature,
        string reason,
        string hint)
    {
        Kind = kind;
        TypeName = typeName;
        MemberName = memberName;
        MemberSignature = memberSignature;
        Reason = reason;
        Hint = hint;
    }

    public string Kind { get; }
    public string TypeName { get; }
    public string MemberName { get; }
    public string MemberSignature { get; }
    public string Reason { get; }
    public string Hint { get; }
}

// ---------------------------------------------------------------------------
// System.Text.Json source-generation context
// ---------------------------------------------------------------------------

[JsonSerializable(typeof(RirFile))]
[JsonSerializable(typeof(RirAssembly))]
[JsonSerializable(typeof(RirNamespace))]
[JsonSerializable(typeof(RirType))]
[JsonSerializable(typeof(RirClass))]
[JsonSerializable(typeof(RirInterface))]
[JsonSerializable(typeof(RirEnum))]
[JsonSerializable(typeof(RirEnumEntry))]
[JsonSerializable(typeof(RirStruct))]
[JsonSerializable(typeof(RirStructComponent))]
[JsonSerializable(typeof(RirMethod))]
[JsonSerializable(typeof(RirProperty))]
[JsonSerializable(typeof(RirParameter))]
[JsonSerializable(typeof(RirConstructor))]
[JsonSerializable(typeof(RirTypeRef))]
[JsonSerializable(typeof(RirVoidType))]
[JsonSerializable(typeof(RirStringType))]
[JsonSerializable(typeof(RirPrimitiveType))]
[JsonSerializable(typeof(RirObjectHandleType))]
[JsonSerializable(typeof(RirEnumType))]
[JsonSerializable(typeof(RirStructType))]
[JsonSerializable(typeof(RirDiagnostic))]
[JsonSourceGenerationOptions(
    WriteIndented = true,
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    DefaultIgnoreCondition = JsonIgnoreCondition.Never)]
internal sealed partial class RirJsonContext : JsonSerializerContext { }
