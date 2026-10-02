namespace Test.Enums;

/// <summary>
/// Reverse enum naming fixture (ADR-006 2026-10-02 amendment): members with acronym runs and a
/// digit boundary, so the generated Kotlin entries (<c>OK</c>, <c>HTTP_TIMEOUT</c>,
/// <c>IO_ERROR</c>, <c>WIN32_NT</c>) are compiled by a real consumer. A separate enum from
/// <see cref="CatMood"/>, whose ordinals other round trips depend on.
/// </summary>
public enum VetTriage
{
    OK,
    HTTPTimeout,
    IOError,
    Win32NT,
}

/// <summary>Moves a triage code one step along Oreo's clinic escalation ladder.</summary>
public class VetTriageDesk
{
    public VetTriage Escalate(VetTriage triage) => triage switch
    {
        VetTriage.OK => VetTriage.HTTPTimeout,
        VetTriage.HTTPTimeout => VetTriage.IOError,
        VetTriage.IOError => VetTriage.Win32NT,
        VetTriage.Win32NT => VetTriage.OK,
        _ => throw new ArgumentOutOfRangeException(nameof(triage), triage, "Unknown triage code"),
    };
}
