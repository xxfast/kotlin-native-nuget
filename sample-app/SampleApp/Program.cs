using SampleLibrary;
using SampleLibrary.Cat;
using SampleLibrary.Math;

Console.WriteLine("=== Kotlin/Native → C# Bridge Demo ===\n");

// Top-level functions (per-file class naming)
Console.WriteLine($"String: {Mappings.@string()}");
Console.WriteLine($"Add: {Arithmetic.add(3, 4)}");
Console.WriteLine($"Divide: {Arithmetic.divide(10, 3)}");
Console.WriteLine($"Divide by zero: {Arithmetic.divide(10, 0)?.ToString() ?? "null"}");

// Nullable strings
Console.WriteLine($"\nOwner of Oreo: {Mappings.nullableString(true)}");
Console.WriteLine($"Owner of nobody: {Mappings.nullableString(false) ?? "null"}");

// Classes with IDisposable
Console.WriteLine("\n=== Classes ===");
using var oreo = new Cat("Oreo", 9);
using var mylo = new Cat("Mylo", 9);

Console.WriteLine(oreo.Meow());
Console.WriteLine($"{mylo.Name} has {mylo.Lives} lives");
Console.WriteLine(mylo.Pet());

// Object-typed properties (brother)
oreo.Brother = mylo;
mylo.Brother = oreo;

using var oreosBrother = oreo.Brother;
Console.WriteLine($"\nOreo's brother: {oreosBrother!.Name}");

// Enums
Console.WriteLine("\n=== Enums ===");
oreo.Mood = Mood.Happy;
Console.WriteLine($"{oreo.Name} is {oreo.Mood}: {oreo.Mood.Description()}");

mylo.Mood = Mood.Sleepy;
Console.WriteLine($"{mylo.Name} is {mylo.Mood}: {mylo.Mood.Description()}");

// Data classes
Console.WriteLine("\n=== Data Classes ===");
using var toy = new Toy("Mouse", "Gray");
Console.WriteLine($"Toy: {toy}");

using var redToy = toy.Copy("Ball", "Red");
Console.WriteLine($"Copy: {redToy}");
Console.WriteLine($"Equal? {toy.Equals(redToy)}");

using var sameToy = toy.Copy("Mouse", "Gray");
Console.WriteLine($"Same values equal? {toy.Equals(sameToy)}");
