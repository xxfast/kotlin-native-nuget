using TestLibrary.Cat;

namespace IntegrationTests;

// Issue #108: a `suspend fun` returning a nullable type generates an unguarded
// `StableRef.create(result)`, so the generated Kotlin does not compile.
//
//   suspend fun findShelterCat(catName: String): Cat?
//     -> val result = findShelterCat(catName)
//        val resultRef = StableRef.create(result).asCPointer()   // result is Cat?, T is Any
//
// Both `SuspendFunctionExports.kt` builders carry the identical `isUnit`-only branch, so this
// file covers both: `AsyncFunctions.*` are top-level (`buildSuspendFunctionBody`) and
// `AsyncCatService.*` are class methods (`buildSuspendMethodBody`). Three return shapes each,
// because they take three different C# extraction paths in `renderAsyncMethod`: a string and an
// int go through `NugetMarshal.FromHandle<T>`, an object goes through `new T(resultPtr)`.
//
// Every cell is asserted in both directions. A fixture that only ever returns non-null cannot
// tell a correct null guard from a missing one.
//
// Expected red state before the fix: the whole package fails to build, with the Kotlin compile
// error above in the generated `CNameExports.kt`.
public class SuspendNullableReturnTests
{
    // --- top-level suspend functions: buildSuspendFunctionBody ---

    [Fact]
    public async Task FindCollarTag_Oreo_ReturnsTag()
    {
        // Oreo keeps his collar on. Nullable string return, non-null branch.
        string? tag = await AsyncFunctions.FindCollarTagAsync("Oreo");
        Assert.Equal("Oreo - black with a white middle", tag);
    }

    [Fact]
    public async Task FindCollarTag_Mylo_ReturnsNull()
    {
        // Mylo has chewed his off. Nullable string return, null branch.
        string? tag = await AsyncFunctions.FindCollarTagAsync("Mylo");
        Assert.Null(tag);
    }

    [Fact]
    public async Task FindShelterCat_Mylo_ReturnsCat()
    {
        // Nullable object return, non-null branch: a live wrapper over a real StableRef.
        using Cat? cat = await AsyncFunctions.FindShelterCatAsync("Mylo");
        Assert.NotNull(cat);
        Assert.Equal("Mylo", cat.Name);
    }

    [Fact]
    public async Task FindShelterCat_Oreo_ReturnsNull()
    {
        // Nullable object return, null branch: null, not a wrapper over IntPtr.Zero.
        using Cat? cat = await AsyncFunctions.FindShelterCatAsync("Oreo");
        Assert.Null(cat);
    }

    [Fact]
    public async Task CountTreatsLeft_Oreo_ReturnsCount()
    {
        // Nullable primitive return, non-null branch.
        int? treats = await AsyncFunctions.CountTreatsLeftAsync("Oreo");
        Assert.Equal(7, treats);
    }

    [Fact]
    public async Task CountTreatsLeft_Mylo_ReturnsNull()
    {
        // Nullable primitive return, null branch. Null must not arrive as a default 0.
        int? treats = await AsyncFunctions.CountTreatsLeftAsync("Mylo");
        Assert.Null(treats);
    }

    // --- suspend class methods: buildSuspendMethodBody ---

    [Fact]
    public async Task FindToyName_Oreo_ReturnsName()
    {
        using var service = new AsyncCatService("catnip");
        string? toy = await service.FindToyNameAsync("Oreo");
        Assert.Equal("catnip mouse", toy);
    }

    [Fact]
    public async Task FindToyName_Mylo_ReturnsNull()
    {
        using var service = new AsyncCatService("catnip");
        string? toy = await service.FindToyNameAsync("Mylo");
        Assert.Null(toy);
    }

    [Fact]
    public async Task FindAdoptedCat_Mylo_ReturnsCat()
    {
        using var service = new AsyncCatService("shelter");
        using Cat? cat = await service.FindAdoptedCatAsync("Mylo");
        Assert.NotNull(cat);
        Assert.Equal("Mylo", cat.Name);
    }

    [Fact]
    public async Task FindAdoptedCat_Oreo_ReturnsNull()
    {
        using var service = new AsyncCatService("shelter");
        using Cat? cat = await service.FindAdoptedCatAsync("Oreo");
        Assert.Null(cat);
    }

    [Fact]
    public async Task CountWhiskers_Oreo_ReturnsCount()
    {
        using var service = new AsyncCatService("grooming");
        int? whiskers = await service.CountWhiskersAsync("Oreo");
        Assert.Equal(24, whiskers);
    }

    [Fact]
    public async Task CountWhiskers_Mylo_ReturnsNull()
    {
        using var service = new AsyncCatService("grooming");
        int? whiskers = await service.CountWhiskersAsync("Mylo");
        Assert.Null(whiskers);
    }
}
