namespace SecondPublisherConsumer;

public static class Dinner
{
    public static void Complain() => TestCompanion.Coexistence.CoexistenceSample.FailCustom();
    public static void Refuse() => TestCompanion.Coexistence.CoexistenceSample.FailMapped();
}
