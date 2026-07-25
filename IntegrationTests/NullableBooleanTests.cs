using TestLibrary.Cat;

namespace IntegrationTests;

// ADR-069: `Boolean?` forward mapping at every position the ADR's table names. Kotlin `Boolean` is
// 1 byte on the native side against C#'s 4-byte default `bool` marshalling, so every case here
// asserts `false` explicitly with `Assert.False(x)` on a `bool?` - never `Assert.NotNull(x)` - to
// catch a 4-byte read of a 1-byte write surfacing a Kotlin `false` as `true`.
//
// Oreo (constructor param + getter, extension seams) and Mylo (the "nothing set yet" null branch)
// carry the class-position cases; `state` ints (0 = true, 1 = false, 2 = null) drive every
// method/function-return seam so the same three-state assertion shape repeats everywhere.
public class NullableBooleanTests
{
    // ---- Cell 1: data-class constructor parameter + read-only property getter ----

    [Fact]
    public void CatChip_Implanted_False()
    {
        using var chip = new CatChip("Oreo", false);
        Assert.False(chip.Implanted);
    }

    [Fact]
    public void CatChip_Implanted_True()
    {
        using var chip = new CatChip("Oreo", true);
        Assert.True(chip.Implanted);
    }

    [Fact]
    public void CatChip_Implanted_Null()
    {
        using var chip = new CatChip("Mylo", null);
        Assert.Null(chip.Implanted);
    }

    // ---- Cell 2: mutable Boolean? property (two-call getter + NullableDispatch setter) ----

    [Fact]
    public void CatChecklist_Vaccinated_DefaultNull()
    {
        using var checklist = new CatChecklist(null);
        Assert.Null(checklist.Vaccinated);
    }

    [Fact]
    public void CatChecklist_Vaccinated_SetFalse()
    {
        using var checklist = new CatChecklist(null);
        checklist.Vaccinated = false;
        Assert.False(checklist.Vaccinated);
    }

    [Fact]
    public void CatChecklist_Vaccinated_SetTrue()
    {
        using var checklist = new CatChecklist(null);
        checklist.Vaccinated = true;
        Assert.True(checklist.Vaccinated);
    }

    [Fact]
    public void CatChecklist_Vaccinated_SetBackToNull()
    {
        using var checklist = new CatChecklist(null);
        checklist.Vaccinated = true;
        checklist.Vaccinated = null;
        Assert.Null(checklist.Vaccinated);
    }

    // ---- Cell 3: instance-method return (single-call valueOut) ----

    [Fact]
    public void CatChecklist_IsGroomed_False()
    {
        using var checklist = new CatChecklist(null);
        Assert.False(checklist.IsGroomed(1));
    }

    [Fact]
    public void CatChecklist_IsGroomed_True()
    {
        using var checklist = new CatChecklist(null);
        Assert.True(checklist.IsGroomed(0));
    }

    [Fact]
    public void CatChecklist_IsGroomed_Null()
    {
        using var checklist = new CatChecklist(null);
        Assert.Null(checklist.IsGroomed(2));
    }

    // ---- Cell 4: nullable Boolean input parameter, non-null return ----

    [Fact]
    public void CatChecklist_Record_False()
    {
        using var checklist = new CatChecklist(null);
        Assert.Equal("false", checklist.Record(false));
    }

    [Fact]
    public void CatChecklist_Record_True()
    {
        using var checklist = new CatChecklist(null);
        Assert.Equal("true", checklist.Record(true));
    }

    [Fact]
    public void CatChecklist_Record_Null()
    {
        using var checklist = new CatChecklist(null);
        Assert.Equal("unknown", checklist.Record(null));
    }

    // ---- Cell 5: object method return ----

    [Fact]
    public void ChipRegistry_IsKnown_False()
    {
        Assert.False(ChipRegistry.IsKnown(1));
    }

    [Fact]
    public void ChipRegistry_IsKnown_True()
    {
        Assert.True(ChipRegistry.IsKnown(0));
    }

    [Fact]
    public void ChipRegistry_IsKnown_Null()
    {
        Assert.Null(ChipRegistry.IsKnown(2));
    }

    // ---- Cell 6: companion method return ----

    [Fact]
    public void CatRecord_IsArchived_False()
    {
        Assert.False(CatRecord.IsArchived(1));
    }

    [Fact]
    public void CatRecord_IsArchived_True()
    {
        Assert.True(CatRecord.IsArchived(0));
    }

    [Fact]
    public void CatRecord_IsArchived_Null()
    {
        Assert.Null(CatRecord.IsArchived(2));
    }

    // ---- Cell 7: top-level function return (two-call route; hard-crashes packNuget today) ----

    [Fact]
    public void NullableBooleanSample_ChipImplanted_False()
    {
        Assert.False(NullableBooleanSample.chipImplanted(1));
    }

    [Fact]
    public void NullableBooleanSample_ChipImplanted_True()
    {
        Assert.True(NullableBooleanSample.chipImplanted(0));
    }

    [Fact]
    public void NullableBooleanSample_ChipImplanted_Null()
    {
        Assert.Null(NullableBooleanSample.chipImplanted(2));
    }

    // ---- Cell 8: top-level function, nullable Boolean input ----

    [Fact]
    public void NullableBooleanSample_DescribeChip_False()
    {
        Assert.Equal("false", NullableBooleanSample.describeChip(false));
    }

    [Fact]
    public void NullableBooleanSample_DescribeChip_True()
    {
        Assert.Equal("true", NullableBooleanSample.describeChip(true));
    }

    [Fact]
    public void NullableBooleanSample_DescribeChip_Null()
    {
        Assert.Equal("unknown", NullableBooleanSample.describeChip(null));
    }

    // ---- Cell 9: top-level mutable Boolean? property ----

    [Fact]
    public void NullableBooleanSample_ChipRegistryOnline_DefaultNull()
    {
        NullableBooleanSample.ChipRegistryOnline = null;
        Assert.Null(NullableBooleanSample.ChipRegistryOnline);
    }

    [Fact]
    public void NullableBooleanSample_ChipRegistryOnline_SetFalse()
    {
        NullableBooleanSample.ChipRegistryOnline = false;
        Assert.False(NullableBooleanSample.ChipRegistryOnline);
    }

    [Fact]
    public void NullableBooleanSample_ChipRegistryOnline_SetTrue()
    {
        NullableBooleanSample.ChipRegistryOnline = true;
        Assert.True(NullableBooleanSample.ChipRegistryOnline);
    }

    // ---- Cell 10: extension-function return ----

    [Fact]
    public void Cat_IsPurringNow_False()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.False(oreo.IsPurringNow(1));
    }

    [Fact]
    public void Cat_IsPurringNow_True()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.True(oreo.IsPurringNow(0));
    }

    [Fact]
    public void Cat_IsPurringNow_Null()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.Null(oreo.IsPurringNow(2));
    }

    // ---- Cell 11: extension-property getter ----

    [Fact]
    public void Cat_HasChip_True_WhenNineLives()
    {
        using var oreo = new Cat("Oreo", 9);
        Assert.True(oreo.GetHasChip());
    }

    [Fact]
    public void Cat_HasChip_False_WhenZeroLives()
    {
        using var mylo = new Cat("Mylo", 0);
        Assert.False(mylo.GetHasChip());
    }

    [Fact]
    public void Cat_HasChip_Null_Otherwise()
    {
        using var stray = new Cat("Stray", 3);
        Assert.Null(stray.GetHasChip());
    }
}
