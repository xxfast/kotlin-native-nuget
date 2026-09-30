using Kotlin.Native.Interop;

namespace SharedExceptionTests;

public class SharedIdentityTests
{
    [Fact]
    public void OneCatchHandlesCustomFailuresFromTwoConsumerAssemblies()
    {
        Assert.NotEqual(typeof(FirstPublisherConsumer.Dinner).Assembly,
            typeof(SecondPublisherConsumer.Dinner).Assembly);
        foreach (Action complain in new Action[]
                 { FirstPublisherConsumer.Dinner.Complain, SecondPublisherConsumer.Dinner.Complain })
        {
            KotlinException caught = Assert.Throws<KotlinException>(complain);
            Assert.EndsWith("wants dinner", caught.Message);
            Assert.Equal(typeof(KotlinException).Assembly, caught.GetType().Assembly);
            Assert.IsType<KotlinArgumentException>(caught.InnerException);
        }
    }

    [Fact]
    public void OneBclCatchHandlesMappedFailuresFromBothConsumerAssemblies()
    {
        foreach (Action refuse in new Action[]
                 { FirstPublisherConsumer.Dinner.Refuse, SecondPublisherConsumer.Dinner.Refuse })
        {
            ArgumentException caught = Assert.ThrowsAny<ArgumentException>(refuse);
            Assert.IsType<KotlinArgumentException>(caught);
            Assert.Equal("kotlin.IllegalArgumentException", ((IKotlinException)caught).KotlinType);
        }
    }
}
