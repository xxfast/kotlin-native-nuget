using System.ComponentModel;
using System.Reflection;
using Kotlin.Native.Interop;
using First = TestLibrary.Coexistence;
using Second = TestCompanion.Coexistence;

namespace MultiPackageTests;

public class CoexistenceTests
{
    [Fact]
    public void BothNativeRuntimesAllocateReadAndDisposeTheirOwnHandles()
    {
        long first = TestLibrary.NugetMarshal.LiveHandles;
        long second = TestCompanion.NugetMarshal.LiveHandles;
        using (var oreo = new First.Probe("Oreo"))
        {
            AssertCounts(first + 1, second);
            using (var mylo = new Second.Probe("Mylo"))
            {
                AssertCounts(first + 1, second + 1);
                Assert.Equal("Oreo", oreo.Name);
                Assert.Equal("Mylo", mylo.Name);
            }
            AssertCounts(first + 1, second);
        }
        AssertCounts(first, second);
    }

    private static void AssertCounts(long first, long second)
    {
        Assert.Equal(first, TestLibrary.NugetMarshal.LiveHandles);
        Assert.Equal(second, TestCompanion.NugetMarshal.LiveHandles);
    }

    [Theory]
    [InlineData(false, "Oreo wants dinner")]
    [InlineData(true, "Mylo wants dinner")]
    public void OneSharedCatchPreservesTypeStackAndCause(bool companion, string message)
    {
        Action fail = companion ? Second.CoexistenceSample.FailCustom : First.CoexistenceSample.FailCustom;
        KotlinException caught = Assert.Throws<KotlinException>(fail);
        Assert.Equal(message, caught.Message);
        Assert.EndsWith(".DinnerComplaint", caught.KotlinType);
        Assert.Contains("DinnerComplaint", caught.KotlinStackTrace);
        var cause = Assert.IsType<KotlinArgumentException>(caught.InnerException);
        Assert.Equal("empty bowl", cause.Message);
        Assert.Equal("kotlin.IllegalArgumentException", cause.KotlinType);
    }

    [Fact]
    public void MappedExceptionsHaveOneIdentityAndRemainCatchableAsBclTypes()
    {
        foreach (Action fail in new Action[]
                 { First.CoexistenceSample.FailMapped, Second.CoexistenceSample.FailMapped })
        {
            ArgumentException caught = Assert.ThrowsAny<ArgumentException>(fail);
            Assert.IsType<KotlinArgumentException>(caught);
            Assert.Equal("kotlin.IllegalArgumentException", ((IKotlinException)caught).KotlinType);
        }
    }

    [Fact]
    public void BothPublishersRegisterTheSameManagedDependencyIndependently()
    {
        Assert.Equal("Oreo meets Mylo", First.CoexistenceSample.ReverseRoundTrip("Mylo"));
        Assert.Equal("Mylo meets Oreo", Second.CoexistenceSample.ReverseRoundTrip("Oreo"));
        Assert.Equal("Oreo meets Isuru", First.CoexistenceSample.ReverseRoundTrip("Isuru"));
    }

    [Fact]
    public void ErasedGenericsRejectForeignWrappersAndLeaveBothCountersUnchanged()
    {
        long first = TestLibrary.NugetMarshal.LiveHandles;
        long second = TestCompanion.NugetMarshal.LiveHandles;
        using (var oreo = new First.Probe("Oreo"))
        using (var mylo = new Second.Probe("Mylo"))
        {
            Assert.Throws<NotSupportedException>(() => new Second.Envelope<First.Probe>(oreo));
            Assert.Throws<NotSupportedException>(() => new First.Envelope<Second.Probe>(mylo));
            AssertCounts(first + 1, second + 1);
            using (var box = new First.Envelope<First.Probe>(oreo))
            using (First.Probe value = box.Value)
            {
                Assert.Equal("Oreo", value.Name);
            }
            using (var box = new Second.Envelope<Second.Probe>(mylo))
            using (Second.Probe value = box.Value)
            {
                Assert.Equal("Mylo", value.Name);
            }
            AssertCounts(first + 1, second + 1);
        }
        AssertCounts(first, second);
    }

    [Fact]
    public void SharedPresenceDistinguishesAbsentNullAndExplicitValuesForBothPublishers()
    {
        KotlinOptional<string?> absent = default;
        KotlinOptional<string?> nil = (string?)null;
        KotlinOptional<string?> owner = "Isuru";
        Assert.False(absent.HasValue);
        Assert.True(nil.HasValue);
        Assert.Null(nil.Value);
        foreach (Func<KotlinOptional<string?>, string> describe in new Func<KotlinOptional<string?>, string>[]
                 { First.CoexistenceSample.Describe, Second.CoexistenceSample.Describe })
        {
            Assert.Equal("nobody", describe(absent));
            Assert.Equal("(none)", describe(nil));
            Assert.Equal("Isuru", describe(owner));
        }
        foreach (Func<KotlinOptional<int?>, string> treats in new Func<KotlinOptional<int?>, string>[]
                 { First.CoexistenceSample.Treats, Second.CoexistenceSample.Treats })
        {
            Assert.Equal("7", treats(default));
            Assert.Equal("(none)", treats((int?)null));
            Assert.Equal("2", treats(2));
        }
        Assert.Equal("nobody", First.CoexistenceSample.Describe());
        Assert.Equal("nobody", Second.CoexistenceSample.Describe());
    }

    [Fact]
    public void BaseConstructorIsClosedWhileGeneratedFactoryAndMappedConstructorsRemainPublic()
    {
        Assert.Empty(typeof(KotlinException).GetConstructors());
        MethodInfo factory = Assert.Single(typeof(KotlinException).GetMethods(), m => m.Name == "Create");
        Assert.Equal(EditorBrowsableState.Never,
            factory.GetCustomAttribute<EditorBrowsableAttribute>()!.State);
        KotlinException made = KotlinException.Create("custom.Cat", "Oreo", "stack");
        Assert.Equal("custom.Cat", made.KotlinType);
        Assert.Equal("Oreo", made.Message);
        Assert.NotEmpty(typeof(KotlinArgumentException).GetConstructors());
        Assert.True(typeof(KotlinArgumentException).IsSealed);
        Assert.Equal("Kotlin.Native.Interop", typeof(KotlinException).Assembly.GetName().Name);
        Assert.Equal(typeof(KotlinException).Assembly, typeof(KotlinOptional<>).Assembly);
    }
}
