package org.zoodb.test;

import static junit.framework.Assert.assertEquals;

import java.util.Collection;

import javax.jdo.PersistenceManager;
import javax.jdo.Query;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.zoodb.jdo.api.Schema;

public class Test_074_QueryComplex {

	@BeforeClass
	public static void setUp() {
        TestTools.removeDb();
		TestTools.createDb();
		TestTools.defineSchema(TestClass.class, TestClassTiny.class, TestClassTiny2.class);
	}

	/**
	 * Both(!) queries below used to return all objects for which (_bool==true) was true.
	 */
	@Test
	public void testExclusiveAnd() {
		PersistenceManager pm = TestTools.openPM();
		pm.currentTransaction().begin();

		TestClass tc1 = new TestClass();
		tc1.setData(1, true, 'c', (byte)127, (short)32000, 1234567890L, "xyz", new byte[]{1,2});
		pm.makePersistent(tc1);
		
		pm.currentTransaction().commit();
		pm.currentTransaction().begin();

		Query q = pm.newQuery(TestClass.class, "_bool == false && _bool == true");
		Collection<?> c = (Collection<?>) q.execute();
		assertEquals(0, c.size());

		Query q2 = pm.newQuery(TestClass.class, "_bool == true && _bool == false");
		Collection<?> c2 = (Collection<?>) q2.execute();
		assertEquals(0, c2.size());

		pm.deletePersistent(tc1);
		pm.currentTransaction().commit();
		
		TestTools.closePM();
	}

	
	@Test
	public void testDeletePersisistentAll() {
		PersistenceManager pm = TestTools.openPM();
		pm.currentTransaction().begin();

		TestClass tc1 = new TestClass();
		tc1.setData(1, true, 'c', (byte)127, (short)32000, 1234567890L, "xyz", new byte[]{1,2});
		pm.makePersistent(tc1);
		tc1 = new TestClass();
		tc1.setData(12, true, 'd', (byte)127, (short)32000, 1234567890L, "xyz", new byte[]{1,2});
		pm.makePersistent(tc1);
		tc1 = new TestClass();
		tc1.setData(123, true, 'e', (byte)127, (short)32000, 1234567890L, "xyz", new byte[]{1,2});
		pm.makePersistent(tc1);
		tc1 = new TestClass();
		tc1.setData(1234, true, 'f', (byte)127, (short)32000, 1234567890L, "xyz", new byte[]{1,2});
		pm.makePersistent(tc1);
		tc1 = new TestClass();
		tc1.setData(12345, true, 'g', (byte)127, (short)32000, 1234567890L, "xyz", new byte[]{1,2});
		pm.makePersistent(tc1);
		
		pm.currentTransaction().commit();
		TestTools.closePM();;
	
		
		//check delete operation
		pm = TestTools.openPM();
		pm.currentTransaction().begin();
		Query q = pm.newQuery(TestClass.class, "_bool == true && _bool == false");
		assertEquals(0, q.deletePersistentAll());
		
		//TODO this should also work without commit!
		pm.currentTransaction().commit();
		pm.currentTransaction().begin();

		q = pm.newQuery(TestClass.class, "_bool == true");
		assertEquals(5, q.deletePersistentAll());

		//test before committing changes
		q = pm.newQuery(TestClass.class, "_bool == true");
		//TODO should return 5 for IgnoreCache=true and 0 for IgnoreCache=false
		if (pm.getIgnoreCache()) {
			assertEquals(5, q.deletePersistentAll());
		} else {
			assertEquals(0, q.deletePersistentAll());
		}

		pm.currentTransaction().commit();
		pm.currentTransaction().begin();

		//now test after committing changes
		q = pm.newQuery(TestClass.class, "_bool == true");
		assertEquals(0, q.deletePersistentAll());

		TestTools.closePM();
		
		//TODO improve:
		//- test that also non-committed objects in cache a flagged as deleted
		//- test that committed objects are only deleted if the modified client version matches
		//  the query.
	}
	
	@Test
	public void testQueryWhereSuperClassDoesNotContainAttribute() {
		PersistenceManager pm = TestTools.openPM();
		pm.currentTransaction().begin();
		
		TestClassTiny t1 = new TestClassTiny();
		pm.makePersistent(t1);
		
		TestClassTiny2 t2 = new TestClassTiny2();
		pm.makePersistent(t2);
		
		pm.currentTransaction().commit();
		pm.currentTransaction().begin();
		
		//now query for i2.
        String filter = "this.i2 == param";
        Query query = pm.newQuery(pm.getExtent(TestClassTiny2.class,true), filter);
        query.declareParameters("int param");
        Collection<?> c = (Collection<?>) query.execute(0);
        assertEquals(1, c.size());

        TestTools.closePM();
	}
	
	@Test
	public void testExcludingSubClass() {
		PersistenceManager pm = TestTools.openPM();
		pm.currentTransaction().begin();
		
		TestClassTiny t1 = new TestClassTiny();
		pm.makePersistent(t1);
		
		TestClassTiny2 t2 = new TestClassTiny2();
		pm.makePersistent(t2);
		
		pm.currentTransaction().commit();
		pm.currentTransaction().begin();
		
		//now query for _int
		//with subs
        String filter = "this._int == param";
        Query query = pm.newQuery(pm.getExtent(TestClassTiny.class, true), filter);
        query.declareParameters("int param");
        Collection<?> c = (Collection<?>) query.execute(0);
        assertEquals(2, c.size());

        //without subs
        filter = "this._int == param";
        query = pm.newQuery(pm.getExtent(TestClassTiny.class, false), filter);
        query.declareParameters("int param");
        c = (Collection<?>) query.execute(0);
        assertEquals(1, c.size());

        TestTools.closePM();
	}
	
	@Test
	public void testExcludingSubClassWithIndex() {
		PersistenceManager pm = TestTools.openPM();
		pm.currentTransaction().begin();

		Schema.locate(pm, TestClassTiny.class).defineIndex("_int", false);
		
		pm.currentTransaction().commit();
		pm.currentTransaction().begin();

		TestClassTiny t1 = new TestClassTiny();
		pm.makePersistent(t1);
		
		TestClassTiny2 t2 = new TestClassTiny2();
		pm.makePersistent(t2);
		
		pm.currentTransaction().commit();
		pm.currentTransaction().begin();
		
		//now query for _int
		//with subs
        String filter = "this._int == param";
        Query query = pm.newQuery(pm.getExtent(TestClassTiny.class, true), filter);
        query.declareParameters("int param");
        Collection<?> c = (Collection<?>) query.execute(0);
        assertEquals(2, c.size());

        //without subs
        filter = "this._int == param";
        query = pm.newQuery(pm.getExtent(TestClassTiny.class, false), filter);
        query.declareParameters("int param");
        c = (Collection<?>) query.execute(0);
        assertEquals(1, c.size());

        TestTools.closePM();
	}
	
	@After
	public void afterTest() {
		TestTools.closePM();
		
		PersistenceManager pm = TestTools.openPM();
		pm.currentTransaction().begin();
		pm.newQuery(TestClassTiny2.class).deletePersistentAll();
		pm.newQuery(TestClassTiny.class).deletePersistentAll();
		pm.currentTransaction().commit();
		TestTools.closePM();
	}
	
	@AfterClass
	public static void tearDown() {
		TestTools.removeDb();
	}
	
}