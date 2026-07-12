namespace Sample.Nullability;

/// <summary>
/// Handle-typed partner for <see cref="NicknameBook"/>: a single public constructor and one
/// read-only property, deliberately clear of <c>Handle</c>/<c>Close</c>/<c>Cleaner</c> (which
/// collide with the ADR-051 Kotlin wrapper's own members). Mirrors <c>Sample.Text.Template</c>'s
/// constructor shape (ADR-052).
/// </summary>
public class Nickname
{
    public Nickname(string value) => Value = value;

    /// <summary>The nickname text, e.g. "Biscuit" for Oreo or "Cream" for Mylo.</summary>
    public string Value { get; }
}

/// <summary>
/// ADR-053 (Phase 9 line 156/157) fixture: exercises every nullable/non-null x
/// string/handle x return/parameter/property combination the nullable-reference-type mapping
/// rules cover. Every member here is compiled under this project's <c>&lt;Nullable&gt;enable&lt;/Nullable&gt;</c>
/// setting, so every reference type carries an explicit <c>NullableAttribute</c> byte.
/// </summary>
public class NicknameBook
{
    /// <summary>
    /// Settable, nullable handle property. Nobody's favourite is recorded by default.
    /// -> Kotlin <c>var favourite: Nickname?</c> (ROADMAP line 157: a handle-typed settable
    /// property is no longer collapsed to a read-only <c>val</c>).
    /// </summary>
    public Nickname? Favourite { get; set; }

    /// <summary>
    /// Settable, non-null handle property. -> Kotlin <c>var primary: Nickname</c> (was
    /// <c>Nickname?</c> under ADR-051's flagged judgment call; corrected here).
    /// </summary>
    public Nickname Primary { get; set; } = new Nickname("Biscuit");

    /// <summary>Settable, nullable string property. -> Kotlin <c>var note: String?</c>.</summary>
    public string? Note { get; set; }

    /// <summary>
    /// Nullable string return, non-null string parameter. Only Oreo has a nickname on record.
    /// -> Kotlin <c>fun find(name: String): String?</c>.
    /// </summary>
    public string? Find(string name) => name == "Oreo" ? "Biscuit" : null;

    /// <summary>
    /// Non-null string return, nullable string parameter: a missing name falls back to
    /// "stranger". -> Kotlin <c>fun greet(name: String?): String</c>.
    /// </summary>
    public string Greet(string? name) => $"Hello, {name ?? "stranger"}";

    /// <summary>
    /// Nullable handle return. Only Mylo has a looked-up nickname.
    /// -> Kotlin <c>fun lookup(name: String): Nickname?</c>.
    /// </summary>
    public Nickname? Lookup(string name) => name == "Mylo" ? new Nickname("Cream") : null;

    /// <summary>
    /// Non-null handle return. -> Kotlin <c>fun defaultNickname(): Nickname</c> (no more
    /// <c>requireNotNull</c> once the metadata reader lands nullability).
    /// </summary>
    public Nickname DefaultNickname() => Primary;

    /// <summary>
    /// Nullable handle parameter: no nickname renders "none".
    /// -> Kotlin <c>fun describe(nickname: Nickname?): String</c>.
    /// </summary>
    public string Describe(Nickname? nickname) => nickname?.Value ?? "none";
}

#nullable disable
/// <summary>
/// Oblivious island inside an otherwise-annotated assembly: every reference type here is
/// byte 0 (no <see cref="System.Runtime.CompilerServices.NullableAttribute"/> anywhere).
/// Binds non-null under ADR-053 decision 1a, and raises one
/// <c>info_oblivious_nullability</c> diagnostic per member.
/// </summary>
public class LegacyNicknameBook
{
    public string Find(string name) => name;
}
#nullable restore
