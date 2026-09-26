using System.Reflection;
using TestLibrary.Issue297;
using KotlinCat = TestLibrary.Cat.Cat;

namespace IntegrationTests;

/// <summary>
/// Issue #297 / ADR-164: every Kotlin defaulted parameter binds as the nullable C# form of its
/// type on ONE signature, so any subset is settable by name and Kotlin evaluates the rest. The
/// trailing all-defaulted run is optional (<c>= null</c>); a default before a required parameter
/// is required-but-nullable; an already-nullable default is an <c>Optional&lt;T&gt;</c> so an
/// explicit <c>null</c> differs from unset. Beyond 8 defaults, only the last 8 widen.
///
/// Oreo's feeder config has four knobs and he only ever touches one of them. Mylo touches none.
/// </summary>
public class Issue297Tests
{
    // ---- Config: one encoding per kind (Guid string, TimeSpan ticks, int, enum) ----

    [Fact]
    public void Config_SettingOnlyTheLastDefault_LeavesTheOtherThreeAtKotlinDefaults()
    {
        // The issue's own example: impossible under the trailing omitting overloads.
        using var oreo = new Config(mode: Mode.Always);

        Assert.Equal(Mode.Always, oreo.Mode);
        Assert.Equal(3, oreo.Retries);
        Assert.Equal(TimeSpan.FromMinutes(1), oreo.Timeout);
        Assert.NotEqual(Guid.Empty, oreo.Id);
    }

    [Fact]
    public void Config_WithNothingSet_EvaluatesUuidRandomPerCall()
    {
        // `Uuid.random()` is evaluated by Kotlin at each call site, never copied into C#, so two
        // default-constructed configs cannot share an id.
        using var oreo = new Config();
        using var mylo = new Config();

        Assert.NotEqual(oreo.Id, mylo.Id);
        Assert.Equal(Mode.Never, mylo.Mode);
    }

    [Fact]
    public void Config_WithEveryValueSet_UsesEveryGivenValue()
    {
        var id = Guid.Parse("0b5e0a11-c0de-4bad-8a75-000000000001");

        using var mylo = new Config(id, TimeSpan.FromSeconds(30), 5, Mode.Always);

        Assert.Equal(id, mylo.Id);
        Assert.Equal(TimeSpan.FromSeconds(30), mylo.Timeout);
        Assert.Equal(5, mylo.Retries);
        Assert.Equal(Mode.Always, mylo.Mode);
    }

    [Fact]
    public void Config_Copy_ReplacesOnlyTheNamedField()
    {
        // Unset on `Copy` means "the receiver's value", which is Kotlin's own `copy` default.
        using var original = new Config(timeout: TimeSpan.FromSeconds(90), mode: Mode.Always);
        using var copy = original.Copy(retries: 7);

        Assert.Equal(7, copy.Retries);
        Assert.Equal(original.Id, copy.Id);
        Assert.Equal(TimeSpan.FromSeconds(90), copy.Timeout);
        Assert.Equal(Mode.Always, copy.Mode);
    }

    [Fact]
    public void Config_HasExactlyOneConstructor_WithFourOptionalNullableParameters()
    {
        ConstructorInfo ctor = Assert.Single(typeof(Config).GetConstructors());
        ParameterInfo[] parameters = ctor.GetParameters();

        Assert.Equal(
            [typeof(Guid?), typeof(TimeSpan?), typeof(int?), typeof(Mode?)],
            parameters.Select(p => p.ParameterType));
        Assert.All(parameters, p => Assert.True(p.IsOptional));
    }

    [Fact]
    public void Config_Copy_HasTheSameWidenedShape()
    {
        MethodInfo copy = Assert.Single(typeof(Config).GetMethods(), m => m.Name == "Copy");
        ParameterInfo[] parameters = copy.GetParameters();

        Assert.Equal(
            [typeof(Guid?), typeof(TimeSpan?), typeof(int?), typeof(Mode?)],
            parameters.Select(p => p.ParameterType));
        Assert.All(parameters, p => Assert.True(p.IsOptional));
    }

    // ---- Registry: already-nullable defaults, Optional<T> ----

    [Fact]
    public void Describe_DistinguishesUnsetFromExplicitNullFromAValue()
    {
        Assert.Equal("Oreo belongs to nobody", Registry.Describe("Oreo"));
        Assert.Equal("Oreo belongs to (none)", Registry.Describe("Oreo", owner: null));
        Assert.Equal("Oreo belongs to Isuru", Registry.Describe("Oreo", owner: "Isuru"));
    }

    [Fact]
    public void Treats_DistinguishesUnsetFromExplicitNullFromAValue()
    {
        Assert.Equal("Mylo gets 7 treats", Registry.Treats("Mylo"));
        Assert.Equal("Mylo gets no treats", Registry.Treats("Mylo", count: null));
        Assert.Equal("Mylo gets 2 treats", Registry.Treats("Mylo", count: 2));
    }

    [Fact]
    public void Describe_OwnerIsAnOptionalOfNullableString()
    {
        MethodInfo describe = Assert.Single(typeof(Registry).GetMethods(), m => m.Name == "Describe");
        ParameterInfo owner = describe.GetParameters()[1];

        Assert.Equal("Optional`1", owner.ParameterType.Name);
        Assert.True(owner.ParameterType.IsValueType);
        Assert.Equal(typeof(string), owner.ParameterType.GetGenericArguments()[0]);
        Assert.True(owner.IsOptional);
    }

    [Fact]
    public void Treats_CountIsAnOptionalOfNullableInt()
    {
        MethodInfo treats = Assert.Single(typeof(Registry).GetMethods(), m => m.Name == "Treats");
        ParameterInfo count = treats.GetParameters()[1];

        Assert.Equal("Optional`1", count.ParameterType.Name);
        Assert.Equal(typeof(int?), count.ParameterType.GetGenericArguments()[0]);
        Assert.True(count.IsOptional);
    }

    // ---- Book: middle default, required-but-nullable ----

    [Fact]
    public void Book_NullPages_UsesKotlinDefault()
    {
        using var book = new Book("Paws", null, "Colombo");

        Assert.Equal("Paws has 100 pages, printed in Colombo", book.Describe());
    }

    [Fact]
    public void Book_GivenPages_UsesThem()
    {
        using var book = new Book("Paws", 12, "Colombo");

        Assert.Equal("Paws has 12 pages, printed in Colombo", book.Describe());
    }

    [Fact]
    public void Book_PagesIsNullableButNotOptional()
    {
        ConstructorInfo ctor = Assert.Single(typeof(Book).GetConstructors());
        ParameterInfo pages = ctor.GetParameters()[1];

        Assert.Equal("pages", pages.Name);
        Assert.Equal(typeof(int?), pages.ParameterType);
        Assert.False(pages.IsOptional);
    }

    // ---- greet: reference-type defaults on the top-level route ----

    [Fact]
    public void Greet_WithOnlyTheName_UsesBothKotlinDefaults()
    {
        // `greeting` rides a null string pointer, `cat` a null handle; Kotlin mints Momo.
        Assert.Equal("hi Mylo, from Momo", Issue297Sample.Greet("Mylo"));
    }

    [Fact]
    public void Greet_SettingOnlyTheCat_KeepsTheGreetingDefault()
    {
        using var oreo = new KotlinCat("Oreo");

        Assert.Equal("hi Mylo, from Oreo", Issue297Sample.Greet("Mylo", cat: oreo));
    }

    [Fact]
    public void Greet_SettingOnlyTheGreeting_KeepsTheCatDefault()
    {
        Assert.Equal("purr Mylo, from Momo", Issue297Sample.Greet("Mylo", greeting: "purr"));
    }

    // ---- Wide: ten defaults, cap of eight ----

    [Fact]
    public void Wide_FirstTwoRequired_LastEightOptional_SumsCorrectly()
    {
        // 1 + 2 + (2 + 3 + 4 + 5 + 6 + 7 + 8) + 99
        using var wide = new Wide(1, 2, p9: 99);

        Assert.Equal(137, wide.Sum());
    }

    [Fact]
    public void Wide_OnlyTheLastEightDefaultsWiden()
    {
        ConstructorInfo ctor = Assert.Single(typeof(Wide).GetConstructors());
        ParameterInfo[] parameters = ctor.GetParameters();

        Assert.Equal(10, parameters.Length);
        foreach (ParameterInfo required in parameters[..2])
        {
            Assert.Equal(typeof(int), required.ParameterType);
            Assert.False(required.IsOptional);
        }

        foreach (ParameterInfo optional in parameters[2..])
        {
            Assert.Equal(typeof(int?), optional.ParameterType);
            Assert.True(optional.IsOptional);
        }
    }

    // ---- Base / Derived: the override inherits the base's default ----

    [Fact]
    public void Derived_Rate_Unset_DispatchesToTheOverrideWithTheBaseDefault()
    {
        using var derived = new Derived();
        Base asBase = derived;

        Assert.Equal(50, derived.Rate());
        Assert.Equal(50, asBase.Rate());
        Assert.Equal(30, derived.Rate(3));
    }

    [Fact]
    public void Base_Rate_Unset_UsesItsOwnBody()
    {
        using var @base = new Base();

        Assert.Equal(5, @base.Rate());
    }

    [Fact]
    public void Rate_BaseAndOverride_ShareOneWidenedSignature()
    {
        MethodInfo onBase = Assert.Single(typeof(Base).GetMethods(), m => m.Name == "Rate");
        MethodInfo onDerived = Assert.Single(
            typeof(Derived).GetMethods(BindingFlags.Public | BindingFlags.Instance | BindingFlags.DeclaredOnly),
            m => m.Name == "Rate");

        ParameterInfo score = Assert.Single(onBase.GetParameters());
        Assert.Equal(typeof(int?), score.ParameterType);
        Assert.True(score.IsOptional);
        Assert.Equal(typeof(int?), Assert.Single(onDerived.GetParameters()).ParameterType);
        Assert.Equal(onBase, onDerived.GetBaseDefinition());
    }

    // ---- Greeter: the interface declares the default, the implementer inherits it ----

    [Fact]
    public void Greeter_ThroughTheInterface_OmittedTimes_UsesTheInterfaceDefault()
    {
        using IGreeter greeter = Issue297Sample.Parrot();

        Assert.Equal("hi Oreo", greeter.Greet("Oreo"));
        Assert.Equal("hi Oreo hi Oreo", greeter.Greet("Oreo", times: 2));
    }

    [Fact]
    public void Greeter_InterfaceAndImplementer_ShareOneWidenedSignature()
    {
        ParameterInfo onInterface = typeof(IGreeter).GetMethod("Greet")!.GetParameters()[1];
        ParameterInfo onParrot = typeof(Parrot).GetMethod("Greet")!.GetParameters()[1];

        Assert.Equal(typeof(int?), onInterface.ParameterType);
        Assert.True(onInterface.IsOptional);
        Assert.Equal(typeof(int?), onParrot.ParameterType);
        Assert.True(onParrot.IsOptional);
    }

    // ---- notify / Button: defaulted lambdas ----

    [Fact]
    public void Notify_OmittedOnDone_UsesTheKotlinDefault()
    {
        Assert.Equal("sent purr", Issue297Sample.Notify("purr"));
    }

    [Fact]
    public void Notify_PassedOnDone_RunsTheLambda()
    {
        string? heard = null;

        Assert.Equal("sent meow", Issue297Sample.Notify("meow", onDone: message => heard = message));
        Assert.Equal("meow", heard);
    }

    [Fact]
    public void Notify_OnDoneIsANullableOptionalDelegate()
    {
        MethodInfo notify = Assert.Single(typeof(Issue297Sample).GetMethods(), m => m.Name == "Notify");
        ParameterInfo onDone = notify.GetParameters()[1];

        Assert.True(onDone.IsOptional);
        Assert.Null(onDone.DefaultValue);
    }

    [Fact]
    public void Button_OmittedArguments_UseKotlinDefaults()
    {
        using var button = new Button();

        Assert.Equal("ok clicked 1", button.Click());
    }

    [Fact]
    public void Button_OnClickIsStored_SoItStaysOffTheConstructor()
    {
        // ADR-160: a constructor keeps the lambda past the call, which a per-call handle cannot
        // survive, so the stored lambda is dropped and Kotlin's default runs.
        ParameterInfo label = Assert.Single(Assert.Single(typeof(Button).GetConstructors()).GetParameters());
        Assert.Equal("label", label.Name);

        using var button = new Button("go");
        Assert.Equal("go clicked 1", button.Click());
    }

    // ---- Button.clicks: `var` with `private set` binds get-only (ADR-075 amendment) ----

    [Fact]
    public void Button_Clicks_ReadsZeroThenOneAfterClick()
    {
        // Oreo stares at the button, then paws it exactly once.
        using var button = new Button();

        Assert.Equal(0, button.Clicks);
        button.Click();
        Assert.Equal(1, button.Clicks);
    }

    [Fact]
    public void Button_Clicks_HasNoSetter()
    {
        // A private Kotlin setter is not API: C# gets a get-only property, not a private set.
        PropertyInfo? clicks = typeof(Button).GetProperty("Clicks");

        Assert.NotNull(clicks);
        Assert.NotNull(clicks!.GetMethod);
        Assert.Null(clicks.SetMethod);
    }
}
