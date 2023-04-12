/*
 * Copyright © 2022,2023 James Crawford
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.jactl.vertx;

import io.jactl.VertxBaseTest;
import io.vertx.junit5.RunTestOnContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
public class JactlVertxTests extends VertxBaseTest {

  @RegisterExtension
  RunTestOnContext rtoc = new RunTestOnContext();

  @BeforeEach
  void prepare(VertxTestContext testContext) {
    init(rtoc.vertx());
    testContext.completeNow();
  }

  @Test void asyncSleep(VertxTestContext testContext) {
    testRunner(testContext)
      .test("sleep(1,2)", 2)
      .test("sleep(1,3)", 3)
      .test("sleep(1,2L)", 2L)
      .test("sleep(1,2D)", 2D)
      .test("sleep(1,2)", 2)
      .test("sleep(1,2.0)", "#2.0")
      .test("sleep(1,[])", List.of())
      .test("sleep(1,[:])", Map.of())
      .test("sleep(1,{it*it})(2)", 4)
      .test("var x=1L; var y=1D; sleep(1,2)", 2)
      .test("sleep(1,2) + sleep(1,3)", 5)
      .test("List l=[1,2]; Map m=[a:1]; String s='asd'; int i=1; long L=1L; double d=1.0D; Decimal D=1.0; sleep(1,2) + l.size() + m.size() + i + L + d + D + sleep(1,3) + l.size() + m.size() + i + L + d + D", "#19.0")
      .test("var l=[1,2]; var m=[a:1]; var s='asd'; var i=1; var L=1L; var d=1.0D; var D=1.0; sleep(1,2) + l.size() + m.size() + i + L + d + D + sleep(1,3) + l.size() + m.size() + i + L + d + D", "#19.0")
      .test("def l=[1,2]; def m=[a:1]; def s='asd'; def i=1; def L=1L; def d=1.0D; def D=1.0; sleep(1,2) + l.size() + m.size() + i + L + d + D + sleep(1,3) + l.size() + m.size() + i + L + d + D", "#19.0")
      .test("sleep(1,sleep(1,2))", 2)
      .test("sleep(sleep(1,1),2)", 2)
      .test("sleep(sleep(1,1),sleep(1,2))", 2)
      .test("sleep(1,sleep(sleep(1,1),2)) + sleep(1,3)", 5)
      .test("def y; y ?= sleep(1,2); y", 2)
      .test("def y; y ?= sleep(1,null); y", null)
      .test("def x; def y; y ?= sleep(1,x)?.size(); y", null)
      .test("def x; def y; y ?= x?.(sleep(1,'si') + sleep(1,'ze'))()?.size(); y", null)
      .test("def x = [1,2,3]; def y; y ?= x?.(sleep(1,'si') + sleep(1,'ze'))(); y", 3)
      .test("def x = [1,2,3]; def y; y ?= x?.(sleep(sleep(1,1),'si') + sleep(sleep(1,1),'ze'))(); y", 3)
      .test("def f(int x) { sleep(1,x) + sleep(1,x) }; f(1 )", 2)
      .test("def f(int x = sleep(1,1)) { sleep(1,x) + sleep(1,x) }; f()", 2)
      .test("def f(long x = sleep(1,1)) { sleep(1,x) + sleep(1,x) }; f()", 2L)
      .test("def f(double x = sleep(1,1)) { sleep(1,x) + sleep(1,x) }; f()", 2D)
      .test("def f(int x = sleep(1,1) + sleep(1,2)) { sleep(1,x) + sleep(1,x) }; f()", 6)
      .test("def f(int x = sleep(sleep(1,1),sleep(1,1))) { sleep(1,x) + sleep(1,x) }; f()", 2)
      .test("def f(x = sleep(1,2),y=sleep(1,x*x)) { x * y }; f()", 8)
      .test("def f(x = sleep(1,2),y=sleep(1,x*x)) { x * y }; f(sleep(1,3),sleep(1,5))", 15)
      .test("def f(x = sleep(1,2),y=sleep(1,x*x)) { sleep(1,x) * sleep(1,y) }; f()", 8)
      .test("def f(x=8) { def g = {sleep(1,it)}; g(x) }; f()", 8)
      .test("def f(x = sleep(1,2),y=sleep(1,x*x)) { def g = { sleep(1,it) + sleep(1,it) }; g(x)*g(y) }; f()", 32)
      .test("\"${sleep(1,2) + sleep(1,3)}\"", "5")
      .test("\"${sleep(1,'a')}:2\"", "a:2")
      .test("\"${sleep(1,'a')}:${sleep(1,2)}\"", "a:2")
      .test("\"${sleep(1,'a')}:${sleep(1,2) + sleep(1,3)}\"", "a:5")
      .test("def x = 0; for (int i=sleep(1,1),j=sleep(1,2*i),k=0; k<sleep(1,5) && i < sleep(1,100); k=sleep(1,k)+1,i=i+sleep(1,1),j=sleep(1,j+1)) { x += sleep(1,k+i+j) }; x", 45)
      .test("sleep(1,2)", 2)
      .test("def f() { sleep(1,1) }; f()", 1)
      .test("def f = sleep; f(0,2)", 2)
      .test("def f(x){sleep(1,x)}; f(sleep(1,2))", 2)
      .test("def f(x){sleep(1,x)}; def g = f; g(2)", 2)
      .test("def f(x){sleep(1,x)}; def g; g = f; g(2)", 2)
      .test("def f(x){sleep(1,x)}; def g; g = f; 2", 2)
      .test("def f(x){sleep(1,x)}; def g = f; g = {it}; g(2)", 2)
      .test("def f(x){x}; def g = f; g = {it}; g(2)", 2)
      .test("def g(x){sleep(1,x)*sleep(1,x)}; def f(x){g(sleep(1,x)*sleep(1,1))*sleep(1,x)}; f(2)", 8)
      .test("def g = {sleep(1,it)*sleep(1,it)}; def f(x,y){g(x)*sleep(1,x)*sleep(1,y)}; f(2,1)", 8)
      .test("def g; g = {it*it}; def f(x){g(x)*x}; f(2)", 8)
      .test("def g = {it*it}; def h = { def f(x){g(x)*x}; f(it) }; h(2)", 8)
      .test("def g = {it*it}; def h = { g(it)*it }; g = {sleep(1,it)*it}; h(2)", 8)
      .test("def g = {it*it}; def h = { def f(x){g(x)*x}; f(it) }; g = {sleep(1,it)*it}; h(2)", 8)
      .test("def g = {it*it}; def h = { def f(x){g(x)*x}; f(it) }; g = {it*it}; h(2)", 8)
      .test("def g = {it*it}; def h = { def f(x){g(x)*x}; g={it*it}; f(it) }; h(2)", 8)
      .test("{it*it*it}(2)", 8)
      .test("{it*it*sleep(1,it)}(2)", 8)
      .test("def f(x=sleep(1,2)){x*x}; f(3)", 9)
      .test("def f(x=sleep(1,2)){x*x}; f()", 4)
      .test("def f(x){g(x)*g(x)}; def g(x){sleep(1,1)+sleep(1,x-1)}; f(2)", 4)
      .test("def f(x){x<=1?1:g(x)}; def g(x){ def h(x){f(x-1)+j(x)}; h(x) }; def j(x){x+x}; f(2)", 5)
      .test("def f(x){x<=1?1:g(x)}; def g(x){ def h(x){f(x-1)+j(x)}; h(x) }; def j(x){sleep(1,x)+sleep(1,x)}; f(2)", 5)
      .test("def f(x){x<=1?1:g(x)}; def g(x){ def h(x){f(x-1)+j(sleep(1,-23)+sleep(1,x+23))}; h(x) }; def j(x){x+x}; f(2)", 5)
      .test("def f(x){x<=1?1:g(x)}; def g(x){ def h(x){f(x-1)+j(x)}; h(x) }; def j(x){f(x-1)+f(x-1)}; f(2)", 3)
      .test("def f(x){x<=1?1:g(sleep(1,x-23)+sleep(1,23))}; def g(x){ def h(x){f(x-1)+j(x)}; h(x) }; def j(x){f(x-1)+f(x-1)}; f(2)", 3)
      .test("def f(x){x<=1?1:g(x)}; def g(x){ def h(x){f(x-1)+j(x)+j(x)}; h(x) }; def j(x){f(sleep(1,x)-1)+f(sleep(1,x)-1)}; f(2)", 5)
      .test("def f = {it}; f(2)", 2)
      .test("def f = {sleep(1,it)+sleep(1,it)}; f(2)", 4)
      .test("def f(x){g(x)}; def g(x){sleep(1,x)+sleep(1,x)}; f(2)+f(3)", 10)
      .test("def s = sleep; def f(x){x<=1?s(0,1):g(x)}; def g(x){s(0,x)+s(0,f(x-1))}; f(2)+f(3)", 9)
      .test("def f(x){x<=1?1:g(x)}; def g(x){def s = sleep; s(0,x) + s(0,f(x-1))}; f(2)+f(3)", 9)
      .test("int i = 1; def f(){ return sleep(1,{ ++i }) }; def g = f(); g() + g()", 5)
      .test("int i = sleep(1,-1)+sleep(1,2); def f(){ return sleep(1,{ sleep(1,++i - 1)+sleep(1,1) }) }; def g = f(); g() + g()", 5)
      .test("int i = 5; def f(int x = sleep(1,1)+sleep(1,1), long y=sleep(1,{x+i}())+sleep(1,{x+i+1}()), double z=3) { sleep(1,x)+sleep(1,y)+sleep(1,z) }; f()", 20D)
      .test("def x = 1; while (true) { (false and break) or x = sleep(1,2); break }; x", 2)
      .test("def x = 1; true and sleep(1, x = 2); x", 2)
      .test("def x = 1; true and sleep(1, x ?= 2); x", 2)
      .test("int x = 1; x += sleep(1,x) + sleep(1,x); x", 3)
      .test("def x = 1; x += sleep(1,x) + sleep(1,x); x", 3)
      .test("def f(int x, long y, String z, double d) { sleep(1,x++); sleep(1,y++); sleep(1,d++); z = sleep(1,z) * sleep(1,x); z + \": x=$x,y=$y,d=$d\" }; f(1,2,'x',3D)", "xx: x=2,y=3,d=4.0")
      .test("int x = 1; long y = 2; double d = 3; sleep(1, d = sleep(1, y = sleep(1, x += sleep(1,x=3)) + x) + y) + x", 20.0D)
      .test("def x = 1; x ?= sleep(1, null as int); x", 1)
      .test("def f = null; f = { null as int }; def x = 1; x ?= sleep(1, 1) + f(); x", 1)
      .test("def f = null; f = { sleep(1,null) as int }; def x = 1; x ?= f(); x", 1)
      .run();
  }

  @Test public void asyncClasses(VertxTestContext testContext) {
    testRunner(testContext)
      .test("class X { int i = 1 }; new X().\"${sleep(1,'i')}\"", 1)
      .test("class X { int abc = 1 }; new X().\"${sleep(1,'a') + sleep(1,'bc')}\"", 1)
      .test("class X { int abc = 1 }; X x = new X(); x.\"${sleep(1,'a') + sleep(1,'bc')}\"", 1)
      .test("class X { int abc = 1 }; def x = new X(); x.\"${sleep(1,'a') + sleep(1,'bc')}\"", 1)
      .test("class X { int abc = sleep(1,1) + sleep(1,new X(abc:3).abc) }; def x = new X(); x.abc", 4)
      .test("class X { int i = sleep(1,-1)+sleep(1,2); def f(){ return sleep(1,{ sleep(1,++i - 1)+sleep(1,1) }) } }; def x = new X(); def g = x.f(); g() + g() + x.i", 8)
      .test("class X { def f() { sleep(1,1) + sleep(1,2) }}; class Y extends X { def f() { sleep(1,5) + sleep(1,4)} }; def y = new Y(); y.f()", 9)
      .test("class X { def f() { 1 + 2 }}; class Y extends X { def f() { super.f() + 5 + 4} }; def y = new Y(); y.f()", 12)
      .test("class X { def f() { sleep(1,1) + sleep(1,2) }}; class Y extends X { def f() { super.f() + sleep(1,5) + sleep(1,4)} }; def y = new Y(); y.f()", 12)
      .test("class X { def f(x) { x == 0 ? 0 : sleep(1,f(x-1)) + sleep(1,x) }}; class Y extends X { def f(x) { sleep(1, super.f(x)) + sleep(1,1) } }; def y = new Y(); y.f(4) + y.f(5)", 15 + 21)
      .test("class X { def f(x) { x == 0 ? 0 : f(x-1) + x }}; class Y extends X { def f(x) { sleep(1, super.f(x)) + sleep(1,1) } }; def y = new Y(); y.f(4) + y.f(5)", 15 + 21)
      .test("class X { Y y = null }; class Y { Z z = null }; class Z { int i = 3; X x = null }; X x = new X(); x.(sleep(1,'y')).z.x.y.z.i = 4; x.y.z.\"${'x'}\".y.z.i", 4)
      .test("class X { Y y = null }; class Y { Z z = null }; class Z { int i = 3; X x = null }; X x = new X(); x.y.(sleep(1,'z')).x.y.z.i = 4; x.y.z.\"${'x'}\".y.z.i", 4)
      .test("class X { Y y = null }; class Y { Z z = null }; class Z { int i = 3; X x = null }; X x = new X(); x.y.z.(sleep(1,'x')).y.z.i = 4; x.y.z.\"${'x'}\".y.z.i", 4)
      .test("class X { Y y = null }; class Y { Z z = null }; class Z { int i = 3; X x = null }; X x = new X(); x.y.z.x.(sleep(1,'y')).z.i = 4; x.y.z.\"${'x'}\".y.z.i", 4)
      .test("class X { Y y = null }; class Y { Z z = null }; class Z { int i = 3; X x = null }; X x = new X(); x.y.z.x.y.(sleep(1,'z')).i = 4; x.y.z.\"${'x'}\".y.z.i", 4)
      .test("class X { Y y = null }; class Y { Z z = null }; class Z { int i = 3; X x = null }; X x = new X(); x.y.z.x.y.z.(sleep(1,'i')) = 4; x.y.z.\"${'x'}\".y.z.i", 4)
      .test("class X { int i = sleep(1,3) }; sleep(1,new X()).i", 3)
      .test("class X { int i }; sleep(1,new X(i:sleep(1,3))).i", 3)
      .test("class X { Y y = null }; class Y { int i = 1 }; def x = new X(); x.y.i = 2", 2)
      .run();
  }

  @Test public void evalWithSleep(VertxTestContext testContext) {
    testRunner(testContext)
      .test("eval('sleep(1,1) + sleep(1,2)') + eval('sleep(1,3) + sleep(1,4)')", 10)
      .test("eval('''sleep(1,1) + eval('sleep(1,2)+sleep(1,-1)')''') + eval('sleep(1,3) + sleep(1,4)')", 9)
      .run();
  }

  @AfterEach
  void cleanUp(VertxTestContext testContext) {
    super.cleanUp();
    testContext.completeNow();
  }
}