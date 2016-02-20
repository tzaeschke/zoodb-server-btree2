/*
 * Copyright 2009-2016 Tilmann Zaeschke. All rights reserved.
 * 
 * This file is part of ZooDB.
 * 
 * ZooDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ZooDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ZooDB.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * See the README and COPYING files for further information. 
 */
package org.zoodb.internal.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Date;

import org.zoodb.api.impl.ZooPC;
import org.zoodb.internal.util.DBLogger;

public class TypeConverterTools {

	
	public static enum COMPARISON_TYPE {
		BIG_DECIMAL(true),
		BIG_INT(true),
		DOUBLE(true),
		FLOAT(true),
		LONG(true),
		INT(true),
		SHORT(true),
		BYTE(true),
		CHAR(true),
		BOOLEAN(false),
		STRING(false),
		DATE(false),
		PC(false),
		SCO(false),
		NULL(true),
		UNKNOWN(true);
		private boolean canBeNumber;
		private COMPARISON_TYPE(boolean canBeNumber) {
			this.canBeNumber = canBeNumber;
		}
		public boolean canBeNumber() {
			return canBeNumber;
		}
		public static COMPARISON_TYPE fromType(Object v) {
			if (v == null || v == QueryTerm.NULL) {
				return NULL;
			}
			return fromTypeClass(v.getClass());
		}
		public static COMPARISON_TYPE fromTypeClass(Class<?> type) {
			if (type == Long.class || type ==  Long.TYPE) {
				return LONG;
			} else if (type ==  Integer.class || type == Integer.TYPE) {
				return INT;
			} else if (type ==  Short.class || type == Short.TYPE) {
				return SHORT;
			} else if (type ==  Byte.class || type == Byte.TYPE) {
				return BYTE;
			} else if (type ==  Double.class || type == Double.TYPE) {
				return DOUBLE;
			} else if (type ==  Float.class || type == Float.TYPE) {
				return FLOAT;
			} else if (type ==  Character.class || type == Character.TYPE) {
				return CHAR;
			} else if (type ==  String.class) {
				return STRING;
			} else if (type ==  Boolean.class || type == Boolean.TYPE) {
				return BOOLEAN;
			} else if (type ==  ZooPC.class) {
				return PC;
			} else if (type ==  Date.class) {
				return DATE;
			} else if (type ==  BigInteger.class) {
				return BIG_INT;
			} else if (type ==  BigDecimal.class) {
				return BIG_DECIMAL;
			}
			return SCO;
		}
		public static COMPARISON_TYPE fromOperands(COMPARISON_TYPE lhsCt,
				COMPARISON_TYPE rhsCt) {
			//swap them (according to ordinal()) to eliminate some 'if'. (?)
			if (rhsCt.ordinal() < lhsCt.ordinal()) {
				COMPARISON_TYPE x = lhsCt;
				lhsCt = rhsCt;
				rhsCt = x;
			}
			
			//TODO use switch/case here?!!
			
			if (lhsCt == SCO || rhsCt == SCO) {
				return SCO;
			}
			if (lhsCt == BIG_DECIMAL) {
//				if (rhsCt == BIG_DECIMAL || rhsCt == BIG_INT || rhsCt == DOUBLE || rhsCt == LONG ||
//						rhsCt == NULL || rhsCt == UNKNOWN) {
				if (rhsCt.canBeNumber()) {
					return BIG_DECIMAL;
				} 
				failComp(lhsCt, rhsCt);
			} 
			if (lhsCt == BIG_INT) {
				if (rhsCt.canBeNumber()) {
					return BIG_INT;
				} 
				failComp(lhsCt, rhsCt);
			}
			if (lhsCt == DOUBLE) {
				if (rhsCt.canBeNumber()) {
					return DOUBLE;
				} 
				failComp(lhsCt, rhsCt);
			}
			if (lhsCt == FLOAT) {
				if (rhsCt.canBeNumber()) {
					return FLOAT;
				} 
				failComp(lhsCt, rhsCt);
			}
			if (lhsCt == LONG) {
				if (rhsCt.canBeNumber()) {
					return LONG;
				} 
				failComp(lhsCt, rhsCt);
			}
			if (lhsCt == INT) {
				if (rhsCt.canBeNumber()) {
					return INT;
				} 
				failComp(lhsCt, rhsCt);
			}
			if (lhsCt == SHORT) {
				if (rhsCt.canBeNumber()) {
					return SHORT;
				} 
				failComp(lhsCt, rhsCt);
			}
			if (lhsCt == BYTE) {
				if (rhsCt.canBeNumber()) {
					return BYTE;
				} 
				failComp(lhsCt, rhsCt);
			}
			if (lhsCt == CHAR) {
				if (rhsCt.canBeNumber()) {
					return CHAR;
				} 
				failComp(lhsCt, rhsCt);
			}

			if (lhsCt == BOOLEAN && rhsCt == BOOLEAN) {
				//TODO check and treat null...
				return BOOLEAN;
			}
			
			if (lhsCt == PC && rhsCt == PC) {
				//TODO check and treat null...
				return PC;
			}
			
			if (lhsCt == STRING && rhsCt == STRING) {
				//TODO check and treat null...
				return STRING;
			}
			
			return UNKNOWN;
		}
		private static void failComp(COMPARISON_TYPE lhsCt,
				COMPARISON_TYPE rhsCt) {
			throw DBLogger.newUser("Cannot compare " + lhsCt + " with " + rhsCt);
		}
	}

	/**
	 * This assumes that comparability implies assignability or convertability...
	 * @param o
	 * @param type
	 */
	public static void checkAssignability(Object o, Class<?> type) {
		COMPARISON_TYPE ctO = COMPARISON_TYPE.fromType(o);
		COMPARISON_TYPE ctT = COMPARISON_TYPE.fromTypeClass(type);
		try {
			COMPARISON_TYPE.fromOperands(ctO, ctT);
		} catch (Exception e) {
			throw DBLogger.newUser("Cannot assign " + o.getClass() + " to " + type, e);
		}
	}
	
	public static double toDouble(Object o) { 
		if (o instanceof Double) {
			return (double)o; 
		} else if (o instanceof Float) {
			return (float)o; 
		} 
		return toLong(o);
	}
	
	public static long toLong(Object o) { 
		if (o instanceof Long) {
			return (long)o; 
		}
		return toInt(o);
	}

	public static int toInt(Object o) { 
		if (o instanceof Integer) {
			return (int)(Integer)o;
		} else if (o instanceof Short) {
			return (Short)o;
		} else if (o instanceof Byte) {
			return (Byte)o;
		} else if (o instanceof Character) {
			return (Character)o;
		}
		throw DBLogger.newUser("Cannot cast type to number: " + o.getClass().getName());
	}
	
	
}
