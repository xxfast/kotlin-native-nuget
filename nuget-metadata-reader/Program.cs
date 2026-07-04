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

        // Group type definitions by namespace, applying include/exclude filters.
        var namespaceMap = new Dictionary<string, List<TypeDefinition>>(StringComparer.Ordinal);
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
                list = new List<TypeDefinition>();
                namespaceMap[ns] = list;
            }

            list.Add(typeDef);
        }

        var rirNamespaces = new List<RirNamespace>();

        foreach (var (ns, typeDefs) in namespaceMap.OrderBy(kv => kv.Key))
        {
            var rirTypes = new List<RirType>();

            foreach (var typeDef in typeDefs)
            {
                var (rirType, typeDiagnostics) = ProcessType(mr, typeDef);
                if (rirType is not null) rirTypes.Add(rirType);
                diagnostics.AddRange(typeDiagnostics);
            }

            if (rirTypes.Count > 0)
                rirNamespaces.Add(new RirNamespace(ns, rirTypes));
        }

        return new RirAssembly(packageId, assemblyName, rirNamespaces, diagnostics);
    }

    private static (RirType? Type, IEnumerable<RirDiagnostic> Diagnostics) ProcessType(
        MetadataReader mr, TypeDefinition typeDef)
    {
        var typeName = mr.GetString(typeDef.Name);
        var isInterface = (typeDef.Attributes & System.Reflection.TypeAttributes.ClassSemanticsMask)
                          == System.Reflection.TypeAttributes.Interface;
        var isAbstract = (typeDef.Attributes & System.Reflection.TypeAttributes.Abstract) != 0;

        var methods = new List<RirMethod>();
        var diagnostics = new List<RirDiagnostic>();

        // --- Methods ---
        // Group by name first to detect overload sets.
        var methodGroups = new Dictionary<string, List<MethodDefinition>>(StringComparer.Ordinal);

        foreach (var handle in typeDef.GetMethods())
        {
            var method = mr.GetMethodDefinition(handle);
            if ((method.Attributes & System.Reflection.MethodAttributes.Public) == 0) continue;

            // Skip property accessors (get_X, set_X) — handled via properties.
            if ((method.Attributes & System.Reflection.MethodAttributes.SpecialName) != 0) continue;

            var name = mr.GetString(method.Name);
            if (!methodGroups.TryGetValue(name, out var group))
            {
                group = new List<MethodDefinition>();
                methodGroups[name] = group;
            }

            group.Add(method);
        }

        foreach (var (methodName, group) in methodGroups)
        {
            if (group.Count > 1)
            {
                // Overload set: skip all, one diagnostic.
                var sig = $"{methodName}({DescribeFirstParams(mr, group[0])}) [+{group.Count - 1} overloads]";
                diagnostics.Add(new RirDiagnostic(
                    kind: "skipped_overload_set",
                    typeName: typeName,
                    memberName: methodName,
                    memberSignature: sig,
                    reason: $"overload set — {group.Count} overloads of `{methodName}` cannot be uniquely exported to C",
                    hint: "Add a C# adapter shim to expose each overload under a distinct name."));
                continue;
            }

            var methodDef = group[0];
            var (rirMethod, methodDiagnostic) = TryMapMethod(mr, typeDef, methodDef, typeName, isInterface);
            if (rirMethod is not null)
                methods.Add(rirMethod);
            else if (methodDiagnostic is not null)
                diagnostics.Add(methodDiagnostic);
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
            if ((getterDef.Attributes & System.Reflection.MethodAttributes.Public) == 0) continue;

            var (propTypeRef, propDiagnostic) = TryDecodePropertyType(mr, propDef, propName, typeName);
            if (propTypeRef is not null)
            {
                bool isReadOnly = accessors.Setter.IsNil
                    || (mr.GetMethodDefinition(accessors.Setter).Attributes
                        & System.Reflection.MethodAttributes.Public) == 0;
                bool isStatic = (getterDef.Attributes & System.Reflection.MethodAttributes.Static) != 0;
                properties.Add(new RirProperty(propName, propTypeRef, isReadOnly, isStatic));
            }
            else if (propDiagnostic is not null)
            {
                diagnostics.Add(propDiagnostic);
            }
        }

        RirType rirType = isInterface
            ? new RirInterface(typeName, methods, properties)
            : new RirClass(typeName, isAbstract && !isInterface, methods, properties);

        return (rirType, diagnostics);
    }

    private static (RirMethod? Method, RirDiagnostic? Diagnostic) TryMapMethod(
        MetadataReader mr,
        TypeDefinition typeDef,
        MethodDefinition methodDef,
        string typeName,
        bool isInterface)
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
                hint: "Override this method in a concrete adapter class."));
        }

        var decoder = new SignatureDecoder(mr);
        MethodSignature<TypeRefOrDiag> sig;

        try
        {
            sig = methodDef.DecodeSignature(decoder, genericContext: null);
        }
        catch (BadImageFormatException)
        {
            return (null, null);
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
                    hint: t.Diagnostic.Hint));
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
                    hint: "This type will be mapped in a future reverse ADR."));
            }

            // Unknown non-async type — skip silently (out of v1 scope, no diagnostic defined).
            return (null, null);
        }

        // Map parameters.
        var parameters = new List<RirParameter>();
        var paramHandles = methodDef.GetParameters().ToList();

        for (int i = 0; i < sig.ParameterTypes.Length; i++)
        {
            var paramTypeRef = sig.ParameterTypes[i].TypeRef;
            if (paramTypeRef is null) return (null, null); // should have been caught above

            string paramName = "arg" + i;
            if (i < paramHandles.Count)
            {
                var paramDef = mr.GetParameter(paramHandles[i]);
                var pn = mr.GetString(paramDef.Name);
                if (!string.IsNullOrEmpty(pn)) paramName = pn;
            }

            parameters.Add(new RirParameter(paramName, paramTypeRef));
        }

        return (new RirMethod(methodName, returnTypeRef, parameters, isStatic), null);
    }

    private static (RirTypeRef? TypeRef, RirDiagnostic? Diagnostic) TryDecodePropertyType(
        MetadataReader mr,
        PropertyDefinition propDef,
        string propName,
        string typeName)
    {
        var decoder = new SignatureDecoder(mr);
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

/// <summary>
/// Implements <see cref="ISignatureTypeProvider{TType,TGenericContext}"/> using
/// <see cref="System.Reflection.Metadata"/> primitives. Maps the v1 type vocabulary to
/// <see cref="TypeRefOrDiag"/>; everything else carries a <see cref="PendingDiagnostic"/>.
/// </summary>
internal sealed class SignatureDecoder : ISignatureTypeProvider<TypeRefOrDiag, object?>
{
    // Fully qualified names of types that carry DynamicAttribute and should be treated as dynamic.
    private const string DynamicAttributeFullName = "System.Runtime.CompilerServices.DynamicAttribute";
    private const string IsByRefLikeAttributeName = "System.Runtime.CompilerServices.IsByRefLikeAttribute";

    private readonly MetadataReader _mr;

    public SignatureDecoder(MetadataReader mr) => _mr = mr;

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
            PrimitiveTypeCode.String  => (RirStringType.Instance, "string"),
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

        if (IsRefStructType(mr, typeDef))
        {
            return new TypeRefOrDiag(null,
                new PendingDiagnostic(
                    "skipped_ref_struct",
                    $"ref struct parameter — `{fullName}` is stack-only (IsByRefLike) and cannot cross the C ABI",
                    "Expose this method in a C# adapter shim with a different parameter type."),
                fullName);
        }

        return new TypeRefOrDiag(null, null, fullName);
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
            return new TypeRefOrDiag(RirStringType.Instance, null, "string");

        return new TypeRefOrDiag(null, null, fullName);
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
        // They're either open generics (handled elsewhere) or instantiated complex types.
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

    private static bool IsRefStructType(MetadataReader mr, TypeDefinition typeDef)
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

    private static string? GetCustomAttributeTypeName(MetadataReader mr, CustomAttribute attr)
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
internal abstract class RirType
{
    public abstract string Name { get; }
}

internal sealed class RirClass : RirType
{
    public RirClass(string name, bool isAbstract, IReadOnlyList<RirMethod> methods, IReadOnlyList<RirProperty> properties)
    {
        Name = name;
        IsAbstract = isAbstract;
        Methods = methods;
        Properties = properties;
    }

    public override string Name { get; }
    public bool IsAbstract { get; }
    public IReadOnlyList<RirMethod> Methods { get; }
    public IReadOnlyList<RirProperty> Properties { get; }
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

[JsonPolymorphic(TypeDiscriminatorPropertyName = "kind")]
[JsonDerivedType(typeof(RirVoidType), "void")]
[JsonDerivedType(typeof(RirStringType), "string")]
[JsonDerivedType(typeof(RirPrimitiveType), "primitive")]
internal abstract class RirTypeRef { }

internal sealed class RirVoidType : RirTypeRef
{
    public static readonly RirVoidType Instance = new();
    private RirVoidType() { }
}

internal sealed class RirStringType : RirTypeRef
{
    public static readonly RirStringType Instance = new();
    private RirStringType() { }
}

internal sealed class RirPrimitiveType : RirTypeRef
{
    public RirPrimitiveType(string name) => Name = name;
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
[JsonSerializable(typeof(RirMethod))]
[JsonSerializable(typeof(RirProperty))]
[JsonSerializable(typeof(RirParameter))]
[JsonSerializable(typeof(RirTypeRef))]
[JsonSerializable(typeof(RirVoidType))]
[JsonSerializable(typeof(RirStringType))]
[JsonSerializable(typeof(RirPrimitiveType))]
[JsonSerializable(typeof(RirDiagnostic))]
[JsonSourceGenerationOptions(
    WriteIndented = true,
    PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase,
    DefaultIgnoreCondition = JsonIgnoreCondition.Never)]
internal sealed partial class RirJsonContext : JsonSerializerContext { }
