// xunit 2 keeps ITestOutputHelper in Xunit.Abstractions; xunit.v3 moved it into Xunit. The shared
// test sources therefore name neither namespace, and each host imports the one it needs. The
// AotIntegrationTests project (xunit.v3) excludes this file.
global using Xunit.Abstractions;
