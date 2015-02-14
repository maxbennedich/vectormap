/**
 * This code is released under the
 * Apache License Version 2.0 http://www.apache.org/licenses/.
 *
 * (c) Daniel Lemire, http://lemire.me/en/
 */
package com.max.integercompression.differential;

import com.max.integercompression.IntegerCODEC;

/**
 * This is just like IntegerCODEC, except that it indicates that delta coding is
 * "integrated", so that you don't need a separate step for delta coding.
 * 
 * @author Daniel Lemire
 */
public interface IntegratedIntegerCODEC extends IntegerCODEC {

}
