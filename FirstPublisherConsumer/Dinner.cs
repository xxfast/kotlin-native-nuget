namespace FirstPublisherConsumer;

public static class Dinner
{
    public static void Complain() => TestLibrary.Coexistence.CoexistenceSample.FailCustom();
    public static void Refuse() => TestLibrary.Coexistence.CoexistenceSample.FailMapped();
}
