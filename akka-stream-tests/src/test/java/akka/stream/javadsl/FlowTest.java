/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import akka.actor.ActorRef;
import akka.dispatch.Foreach;
import akka.dispatch.Futures;
import akka.japi.JavaPartialFunction;
import akka.japi.Pair;
import akka.japi.function.*;
import akka.stream.*;
import akka.stream.javadsl.FlowGraph.Builder;
import akka.stream.stage.*;
import akka.stream.testkit.AkkaSpec;
import akka.stream.testkit.TestPublisher;
import akka.testkit.JavaTestKit;
import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Publisher;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static akka.stream.testkit.StreamTestKit.PublisherProbeSubscription;
import static org.junit.Assert.assertEquals;

@SuppressWarnings("serial")
public class FlowTest extends StreamTest {
  public FlowTest() {
    super(actorSystemResource);
  }

    @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowTest",
      AkkaSpec.testConf());

  @Test
  public void mustBeAbleToUseSimpleOperators() {
    final JavaTestKit probe = new JavaTestKit(system);
    final String[] lookup = { "a", "b", "c", "d", "e", "f" };
    final java.lang.Iterable<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5);
    final Source<Integer, ?> ints = Source.from(input);
    final Flow<Integer, String, ?> flow1 = Flow.of(Integer.class).drop(2).take(3
    ).takeWithin(FiniteDuration.create(10, TimeUnit.SECONDS
    )).map(new Function<Integer, String>() {
      public String apply(Integer elem) {
        return lookup[elem];
      }
    }).filter(new Predicate<String>() {
      public boolean test(String elem) {
        return !elem.equals("c");
      }
    });
    final Flow<String, String, ?> flow2 = Flow.of(String.class).grouped(2
    ).mapConcat(new Function<java.util.List<String>, java.lang.Iterable<String>>() {
      public java.util.List<String> apply(java.util.List<String> elem) {
        return elem;
      }
    }).groupedWithin(100, FiniteDuration.create(50, TimeUnit.MILLISECONDS)
    ).mapConcat(new Function<java.util.List<String>, java.lang.Iterable<String>>() {
          public java.util.List<String> apply(java.util.List<String> elem) {
            return elem;
          }
        });

    ints.via(flow1.via(flow2)).runFold("", new Function2<String, String, String>() {
          public String apply(String acc, String elem) {
            return acc + elem;
          }
        }, materializer
    ).foreach(new Foreach<String>() { // Scala Future
              public void each(String elem) {
                probe.getRef().tell(elem, ActorRef.noSender());
              }
            }, system.dispatcher());

    probe.expectMsgEquals("de");
  }

  @Test
  public void mustBeAbleToUseDropWhile() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<Integer, ?> source = Source.from(Arrays.asList(0, 1, 2, 3));
    final Flow<Integer, Integer, ?> flow = Flow.of(Integer.class).dropWhile
            (new Predicate<Integer>() {
              public boolean test(Integer elem) {
                return elem < 2;
              }
            });

    final Future<BoxedUnit> future = source.via(flow).runWith(Sink.foreach(new Procedure<Integer>() { // Scala Future
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }), materializer);

    probe.expectMsgEquals(2);
    probe.expectMsgEquals(3);
    Await.ready(future, Duration.apply(200, TimeUnit.MILLISECONDS));
  }

  @Test
  public void mustBeAbleToUseTakeWhile() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Source<Integer, ?> source = Source.from(Arrays.asList(0, 1, 2, 3));
    final Flow<Integer, Integer, ?> flow = Flow.of(Integer.class).takeWhile
            (new Predicate<Integer>() {
              public boolean test(Integer elem) {
                return elem < 2;
              }
            });

    final Future<BoxedUnit> future = source.via(flow).runWith(Sink.foreach(new Procedure<Integer>() { // Scala Future
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }), materializer);

    probe.expectMsgEquals(0);
    probe.expectMsgEquals(1);

    FiniteDuration duration = Duration.apply(200, TimeUnit.MILLISECONDS);

    probe.expectNoMsg(duration);
    Await.ready(future, duration);
  }


  @Test
  public void mustBeAbleToUseTransform() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input = Arrays.asList(0, 1, 2, 3, 4, 5, 6, 7);
    // duplicate each element, stop after 4 elements, and emit sum to the end
    final Flow<Integer, Integer, ?> flow = Flow.of(Integer.class).transform(new Creator<Stage<Integer, Integer>>() {
      @Override
      public PushPullStage<Integer, Integer> create() throws Exception {
        return new StatefulStage<Integer, Integer>() {
          int sum = 0;
          int count = 0;

          @Override
          public StageState<Integer, Integer> initial() {
            return new StageState<Integer, Integer>() {
              @Override
              public SyncDirective onPush(Integer element, Context<Integer> ctx) {
                sum += element;
                count += 1;
                if (count == 4) {
                  return emitAndFinish(Arrays.asList(element, element, sum).iterator(), ctx);
                } else {
                  return emit(Arrays.asList(element, element).iterator(), ctx);
                }
              }
              
            };
          }
          
          @Override
          public TerminationDirective onUpstreamFinish(Context<Integer> ctx) {
            return terminationEmit(Collections.singletonList(sum).iterator(), ctx);
          }
          
        };
      }
    });
    Source.from(input).via(flow).runForeach(new Procedure<Integer>() {
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);

    probe.expectMsgEquals(0);
    probe.expectMsgEquals(0);
    probe.expectMsgEquals(1);
    probe.expectMsgEquals(1);
    probe.expectMsgEquals(2);
    probe.expectMsgEquals(2);
    probe.expectMsgEquals(3);
    probe.expectMsgEquals(3);
    probe.expectMsgEquals(6);
  }

  @Test
  public void mustBeAbleToUseGroupBy() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("Aaa", "Abb", "Bcc", "Cdd", "Cee");
    final Flow<String, Pair<String, Source<String, BoxedUnit>>, BoxedUnit> slsFlow = Flow
        .of(String.class).groupBy(new Function<String, String>() {
          public String apply(String elem) {
            return elem.substring(0, 1);
          }
        });
    Source.from(input).via(slsFlow).runForeach(new Procedure<Pair<String, Source<String, BoxedUnit>>>() {
      @Override
      public void apply(final Pair<String, Source<String, BoxedUnit>> pair) throws Exception {
        pair.second().runForeach(new Procedure<String>() {
          @Override
          public void apply(String elem) throws Exception {
            probe.getRef().tell(new Pair<String, String>(pair.first(), elem), ActorRef.noSender());
          }
        }, materializer);
      }
    }, materializer);

    Map<String, List<String>> grouped = new HashMap<String, List<String>>();
    for (Object o : probe.receiveN(5)) {
      @SuppressWarnings("unchecked")
      Pair<String, String> p = (Pair<String, String>) o;
      List<String> g = grouped.get(p.first());
      if (g == null) {
        g = new ArrayList<String>();
      }
      g.add(p.second());
      grouped.put(p.first(), g);
    }

    assertEquals(Arrays.asList("Aaa", "Abb"), grouped.get("A"));

  }

  @Test
  public void mustBeAbleToUseSplitWhen() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("A", "B", "C", ".", "D", ".", "E", "F");
    final Flow<String, Source<String, BoxedUnit>, ?> flow = Flow.of(String.class).splitWhen(new Predicate<String>() {
      public boolean test(String elem) {
        return elem.equals(".");
      }
    });
    Source.from(input).via(flow).runForeach(new Procedure<Source<String, BoxedUnit>>() {
      @Override
      public void apply(Source<String, BoxedUnit> subStream) throws Exception {
        subStream.filter(new Predicate<String>() {
          @Override
          public boolean test(String elem) {
            return !elem.equals(".");
          }
        }).grouped(10).runForeach(new Procedure<List<String>>() {
          @Override
          public void apply(List<String> chunk) throws Exception {
            probe.getRef().tell(chunk, ActorRef.noSender());
          }
        }, materializer);
      }
    }, materializer);

    for (Object o : probe.receiveN(3)) {
      @SuppressWarnings("unchecked")
      List<String> chunk = (List<String>) o;
      if (chunk.get(0).equals("A")) {
        assertEquals(Arrays.asList("A", "B", "C"), chunk);
      } else if (chunk.get(0).equals("D")) {
        assertEquals(Arrays.asList("D"), chunk);
      } else if (chunk.get(0).equals("E")) {
        assertEquals(Arrays.asList("E", "F"), chunk);
      } else {
        assertEquals("[A, B, C] or [D] or [E, F]", chunk);
      }
    }

  }

  @Test
  public void mustBeAbleToUseSplitAfter() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("A", "B", "C", ".", "D", ".", "E", "F");
    final Flow<String, Source<String, BoxedUnit>, ?> flow = Flow.of(String.class).splitAfter(new Predicate<String>() {
      public boolean test(String elem) {
        return elem.equals(".");
      }
    });

    Source.from(input).via(flow).runForeach(new Procedure<Source<String, BoxedUnit>>() {
      @Override
      public void apply(Source<String, BoxedUnit> subStream) throws Exception {
        subStream.grouped(10).runForeach(new Procedure<List<String>>() {
          @Override
          public void apply(List<String> chunk) throws Exception {
            probe.getRef().tell(chunk, ActorRef.noSender());
          }
        }, materializer);
      }
    }, materializer);

    for (Object o : probe.receiveN(3)) {
      @SuppressWarnings("unchecked")
      List<String> chunk = (List<String>) o;
      if (chunk.get(0).equals("A")) {
        assertEquals(Arrays.asList("A", "B", "C", "."), chunk);
      } else if (chunk.get(0).equals("D")) {
        assertEquals(Arrays.asList("D", "."), chunk);
      } else if (chunk.get(0).equals("E")) {
        assertEquals(Arrays.asList("E", "F"), chunk);
      } else {
        assertEquals("[A, B, C, .] or [D, .] or [E, F]", chunk);
      }
    }

  }

  public <T> Creator<Stage<T, T>> op() {
    return new akka.japi.function.Creator<Stage<T, T>>() {
      @Override
      public PushPullStage<T, T> create() throws Exception {
        return new PushPullStage<T, T>() {  
          @Override
          public SyncDirective onPush(T element, Context<T> ctx) {
            return ctx.push(element);
          }
          
          @Override
          public SyncDirective onPull(Context<T> ctx) {
            return ctx.pull();
          }
        };
      }
    };
  }

  @Test
  public void mustBeAbleToUseMerge() throws Exception {
    final Flow<String, String, BoxedUnit> f1 =
        Flow.of(String.class).transform(FlowTest.this.<String> op()).named("f1");
    final Flow<String, String, BoxedUnit> f2 = 
        Flow.of(String.class).transform(FlowTest.this.<String> op()).named("f2");
    @SuppressWarnings("unused")
    final Flow<String, String, BoxedUnit> f3 = 
        Flow.of(String.class).transform(FlowTest.this.<String> op()).named("f3");

    final Source<String, BoxedUnit> in1 = Source.from(Arrays.asList("a", "b", "c"));
    final Source<String, BoxedUnit> in2 = Source.from(Arrays.asList("d", "e", "f"));

    final Sink<String, Publisher<String>> publisher = Sink.publisher();
    
    final Source<String, BoxedUnit> source = Source.factory().create(new Function<FlowGraph.Builder<BoxedUnit>, Outlet<String>>() {
      @Override
      public Outlet<String> apply(Builder<BoxedUnit> b) throws Exception {
        final UniformFanInShape<String, String> merge = b.graph(Merge.<String> create(2));
        b.flow(b.source(in1), f1, merge.in(0));
        b.flow(b.source(in2), f2, merge.in(1));
        return merge.out();
      }
    });

    // collecting
    final Publisher<String> pub = source.runWith(publisher, materializer);
    final Future<List<String>> all = Source.from(pub).grouped(100).runWith(Sink.<List<String>>head(), materializer);

    final List<String> result = Await.result(all, Duration.apply(200, TimeUnit.MILLISECONDS));
    assertEquals(new HashSet<Object>(Arrays.asList("a", "b", "c", "d", "e", "f")), new HashSet<String>(result));
  }

  @Test
  public void mustBeAbleToUseZip() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<Integer> input2 = Arrays.asList(1, 2, 3);

    final Builder<BoxedUnit> b = FlowGraph.<BoxedUnit>builder();
    final Outlet<String> in1 = b.source(Source.from(input1));
    final Outlet<Integer> in2 = b.source(Source.from(input2));
    final FanInShape2<String, Integer, Pair<String, Integer>> zip = b.graph(Zip.<String, Integer> create());
    final Inlet<Pair<String, Integer>> out = b.sink(Sink
        .foreach(new Procedure<Pair<String, Integer>>() {
          @Override
          public void apply(Pair<String, Integer> param) throws Exception {
            probe.getRef().tell(param, ActorRef.noSender());
          }
        }));
    
    b.edge(in1, zip.in0());
    b.edge(in2, zip.in1());
    b.edge(zip.out(), out);
    
    b.run(materializer);

    List<Object> output = Arrays.asList(probe.receiveN(3));
    @SuppressWarnings("unchecked")
    List<Pair<String, Integer>> expected = Arrays.asList(new Pair<String, Integer>("A", 1), new Pair<String, Integer>(
        "B", 2), new Pair<String, Integer>("C", 3));
    assertEquals(expected, output);
  }

  @Test
  public void mustBeAbleToUseConcat() {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input1 = Arrays.asList("A", "B", "C");
    final Iterable<String> input2 = Arrays.asList("D", "E", "F");

    final Source<String, ?> in1 = Source.from(input1);
    final Source<String, ?> in2 = Source.from(input2);
    final Flow<String, String, ?> flow = Flow.of(String.class);
    in1.via(flow.concat(in2)).runForeach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);

    List<Object> output = Arrays.asList(probe.receiveN(6));
    assertEquals(Arrays.asList("A", "B", "C", "D", "E", "F"), output);
  }

  @Test
  public void mustBeAbleToUsePrefixAndTail() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input = Arrays.asList(1, 2, 3, 4, 5, 6);
    final Flow<Integer, Pair<List<Integer>, Source<Integer, BoxedUnit>>, ?> flow = Flow.of(Integer.class).prefixAndTail(3);
    Future<Pair<List<Integer>, Source<Integer, BoxedUnit>>> future =
        Source.from(input).via(flow).runWith(Sink.<Pair<List<Integer>, Source<Integer, BoxedUnit>>>head(), materializer);
    Pair<List<Integer>, Source<Integer, BoxedUnit>> result = Await.result(future,
        probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals(Arrays.asList(1, 2, 3), result.first());

    Future<List<Integer>> tailFuture = result.second().grouped(4).runWith(Sink.<List<Integer>>head(), materializer);
    List<Integer> tailResult = Await.result(tailFuture, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals(Arrays.asList(4, 5, 6), tailResult);
  }

  @Test
  public void mustBeAbleToUseConcatAllWithSources() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<Integer> input1 = Arrays.asList(1, 2, 3);
    final Iterable<Integer> input2 = Arrays.asList(4, 5);

    final List<Source<Integer, BoxedUnit>> mainInputs = new ArrayList<Source<Integer,BoxedUnit>>();
    mainInputs.add(Source.from(input1));
    mainInputs.add(Source.from(input2));

    final Flow<Source<Integer, BoxedUnit>, List<Integer>, BoxedUnit> flow = Flow.<Source<Integer, BoxedUnit>>create().
      flatten(akka.stream.javadsl.FlattenStrategy.<Integer, BoxedUnit> concat()).grouped(6);
    Future<List<Integer>> future = Source.from(mainInputs).via(flow)
        .runWith(Sink.<List<Integer>>head(), materializer);

    List<Integer> result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));

    assertEquals(Arrays.asList(1, 2, 3, 4, 5), result);
  }

  @Test
  public void mustBeAbleToUseBuffer() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    final Flow<String, List<String>, BoxedUnit> flow = Flow.of(String.class).buffer(2, OverflowStrategy.backpressure()).grouped(4);
    Future<List<String>> future = Source.from(input).via(flow)
        .runWith(Sink.<List<String>>head(), materializer);

    List<String> result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals(input, result);
  }

  @Test
  public void mustBeAbleToUseConflate() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    final Flow<String, String, BoxedUnit> flow = Flow.of(String.class).conflate(new Function<String, String>() {
      @Override
      public String apply(String s) throws Exception {
        return s;
      }
    }, new Function2<String, String, String>() {
      @Override
      public String apply(String aggr, String in) throws Exception {
        return aggr + in;
      }
    });
    Future <String> future = Source.from(input).via(flow).runFold("", new Function2<String, String, String>() {
      @Override
      public String apply(String aggr, String in) throws Exception {
        return aggr + in;
      }
    }, materializer);
    String result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals("ABC", result);
  }

  @Test
  public void mustBeAbleToUseExpand() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final List<String> input = Arrays.asList("A", "B", "C");
    final Flow<String, String, BoxedUnit> flow = Flow.of(String.class).expand(new Function<String, String>() {
      @Override
      public String apply(String in) throws Exception {
        return in;
      }
    }, new Function<String, Pair<String, String>>() {
      @Override
      public Pair<String, String> apply(String in) throws Exception {
        return new Pair<String, String>(in, in);
      }
    });
    final Sink<String, Future<String>> sink = Sink.<String>head();
    Future<String> future = Source.from(input).via(flow).runWith(sink, materializer);
    String result = Await.result(future, probe.dilated(FiniteDuration.create(3, TimeUnit.SECONDS)));
    assertEquals("A", result);
  }

  @Test
  public void mustBeAbleToUseMapAsync() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Iterable<String> input = Arrays.asList("a", "b", "c");
    final Flow<String, String, BoxedUnit> flow = Flow.of(String.class).mapAsync(4, new Function<String, Future<String>>() {
      public Future<String> apply(String elem) {
        return Futures.successful(elem.toUpperCase());
      }
    });
    Source.from(input).via(flow).runForeach(new Procedure<String>() {
      public void apply(String elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }, materializer);
    probe.expectMsgEquals("A");
    probe.expectMsgEquals("B");
    probe.expectMsgEquals("C");
  }

  @Test
  public void mustBeAbleToRecover() throws Exception {
    final TestPublisher.ManualProbe<Integer> publisherProbe = TestPublisher.manualProbe(true,system);
    final JavaTestKit probe = new JavaTestKit(system);

    final Source<Integer, ?> source = Source.from(publisherProbe);
    final Flow<Integer, Integer, ?> flow = Flow.of(Integer.class).map(
            new Function<Integer, Integer>() {
              public Integer apply(Integer elem) {
                if (elem == 2) throw new RuntimeException("ex");
                else return elem;
              }
            })
            .recover(new JavaPartialFunction<Throwable, Integer>() {
              public Integer apply(Throwable elem, boolean isCheck) {
                if (isCheck) return null;
                return 0;
              }
            });

    final Future<BoxedUnit> future = source.via(flow).runWith(Sink.foreach(new Procedure<Integer>() {
      public void apply(Integer elem) {
        probe.getRef().tell(elem, ActorRef.noSender());
      }
    }), materializer);

    final PublisherProbeSubscription<Integer> s = publisherProbe.expectSubscription();

    s.sendNext(0);
    probe.expectMsgEquals(0);
    s.sendNext(1);
    probe.expectMsgEquals(1);
    s.sendNext(2);
    probe.expectMsgEquals(0);
    Await.ready(future, Duration.apply(200, TimeUnit.MILLISECONDS));
  }

}
