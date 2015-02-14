/**
 * This code is released under the
 * Apache License Version 2.0 http://www.apache.org/licenses/.
 *
 * (c) Daniel Lemire, http://lemire.me/en/
 */

package com.max.integercompression.differential;

import com.max.integercompression.ByteIntegerCODEC;

/**
 * Interface describing a CODEC to compress integers to bytes.
 * 
 * "Integrated" means that it uses differential coding.
 * 
 * @author Daniel Lemire
 * 
 */
public interface IntegratedByteIntegerCODEC extends ByteIntegerCODEC {
}