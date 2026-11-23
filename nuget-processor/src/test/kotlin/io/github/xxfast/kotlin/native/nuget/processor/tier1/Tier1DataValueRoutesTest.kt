package io.github.xxfast.kotlin.native.nuget.processor.tier1

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ADR-242 Phase B: the shapes that bound while a scalar data class was a handle keep binding now
 * it is a value. A value as a parameter of the legacy `suspend`/`Flow` routes, a value's own
 * `suspend`/`Flow` members, a `suspend` extension on a value, and a value class over a value. The
 * generated C# must compile, and none of them may skip.
 */
class Tier1DataValueRoutesTest {

  private val fixture: String = """
    package tier1.valueroutes

    import kotlinx.coroutines.delay
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.flow

    data class Toy(val name: String, val squeaks: Int)

    @JvmInline
    value class Ticket(val toy: Toy)

    fun ticketFor(toy: Toy): Ticket = Ticket(toy)
    fun redeem(ticket: Ticket): Toy = ticket.toy
    fun maybeRedeem(ticket: Ticket?): Toy? = ticket?.toy

    val Toy.shout: String get() = name.uppercase()
    fun Toy.hug(): String = "hug " + name

    suspend fun Toy.nap(): String {
      delay(1)
      return name + " naps"
    }

    suspend fun weigh(toy: Toy): Int {
      delay(1)
      return toy.squeaks * 10
    }

    fun parade(toy: Toy): Flow<Int> = flow { emit(toy.squeaks) }

    class Nursery {
      var ticket: Ticket = Ticket(Toy("Bun", 1))

      suspend fun stash(toy: Toy): Int {
        delay(1)
        return toy.squeaks
      }

      suspend fun maybeStash(toy: Toy?): Int = toy?.squeaks ?: 0

      suspend fun stashTicket(ticket: Ticket): Int = ticket.toy.squeaks

      fun watchTicket(ticket: Ticket): Flow<Int> = flow { emit(ticket.toy.squeaks) }

      fun watch(toy: Toy): Flow<Int> = flow { emit(toy.squeaks) }

      fun visit(toy: Toy, block: (String) -> Unit) = block(toy.name)
    }

    data class Napping(val name: String) {
      suspend fun snooze(): String {
        delay(1)
        return name + " snoozes"
      }

      fun fidget(): Flow<Int> = flow { emit(1) }

      fun forEachLetter(block: (Char) -> Unit) = name.forEach(block)
    }
  """.trimIndent()

  private val result: Tier1Result by lazy {
    Tier1Harness.run(
      fixture,
      fileName = "Routes.kt",
      processorOptions = mapOf("nuget.namespace" to "Tier1"),
      libraries = listOf(Tier1Classpath.kotlinxCoroutinesCore),
    )
  }

  @Test
  fun `no value shape skips`() {
    assertTrue(result.kspErrors.isEmpty(), "kspErrors=${result.kspErrors}")
    val skipped: List<String> = result.kspWarnings.filter { "[nuget:SKIPPED_" in it }
    assertTrue(skipped.isEmpty(), "expected every member bound; skipped=\n${skipped.joinToString("\n")}")
  }

  @Test
  fun `the generated Kotlin compiles`() {
    assertTrue(result.compiledClean, "compileErrors=${result.compileErrors}")
  }

  @Test
  fun `a consumer of every route compiles against the generated C#`() {
    val consumer = """
      using System;
      using System.Collections.Generic;
      using System.Threading.Tasks;
      using Tier1;
      public static class Consumer
      {
          public static async Task Touch()
          {
              Toy toy = new Toy("Bun", 3);
              Ticket ticket = Routes.TicketFor(toy);
              Toy back = Routes.Redeem(ticket);
              Toy? maybe = Routes.MaybeRedeem(null);
              string shout = toy.Shout;
              string hug = toy.Hug();
              string nap = await toy.NapAsync();
              int weight = await Routes.WeighAsync(toy);
              await foreach (int squeak in Routes.Parade(toy)) { }
              using var nursery = new Nursery();
              nursery.Ticket = ticket;
              Ticket held = nursery.Ticket;
              int stashed = await nursery.StashAsync(toy);
              int maybeStashed = await nursery.MaybeStashAsync(null);
              int ticketStashed = await nursery.StashTicketAsync(ticket);
              await foreach (int squeak in nursery.WatchTicket(ticket)) { }
              await foreach (int squeak in nursery.Watch(toy)) { }
              nursery.Visit(toy, name => { });
              Napping napping = new Napping("Milo");
              string snooze = await napping.SnoozeAsync();
              await foreach (int fidget in napping.Fidget()) { }
              napping.ForEachLetter(letter => { });
          }
      }
    """.trimIndent()
    val build: Tier1CSharpBuild = Tier1CSharpCompile.compile(result, consumer)
    assertTrue(build.succeeded, "expected the consumer to compile; log:\n${build.log}")
  }
}
