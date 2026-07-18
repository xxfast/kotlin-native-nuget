using TestLibrary.Clinic;

namespace IntegrationTests;

public class MethodParameterMarshallingTests
{
    [Fact]
    public void Patient_EchoName_MarshalsStringParameterAndReturn()
    {
        using var patient = new Patient("Oreo");

        Assert.Equal("Mylo", patient.EchoName("Mylo"));
    }
}
