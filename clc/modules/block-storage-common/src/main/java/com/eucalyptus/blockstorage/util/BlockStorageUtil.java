/*************************************************************************
 * Copyright 2009-2012 Eucalyptus Systems, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 * Please contact Eucalyptus Systems, Inc., 6755 Hollister Ave., Goleta
 * CA 93117, USA or visit http://www.eucalyptus.com/licenses/ if you need
 * additional information or have any questions.
 *
 * This file may incorporate work covered under the following copyright
 * and permission notice:
 *
 *   Software License Agreement (BSD License)
 *
 *   Copyright (c) 2008, Regents of the University of California
 *   All rights reserved.
 *
 *   Redistribution and use of this software in source and binary forms,
 *   with or without modification, are permitted provided that the
 *   following conditions are met:
 *
 *     Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *
 *     Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer
 *     in the documentation and/or other materials provided with the
 *     distribution.
 *
 *   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 *   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 *   FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 *   COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 *   INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 *   BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 *   LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 *   CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 *   LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 *   ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 *   POSSIBILITY OF SUCH DAMAGE. USERS OF THIS SOFTWARE ACKNOWLEDGE
 *   THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE LICENSED MATERIAL,
 *   COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS SOFTWARE,
 *   AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *   IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA,
 *   SANTA BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY,
 *   WHICH IN THE REGENTS' DISCRETION MAY INCLUDE, WITHOUT LIMITATION,
 *   REPLACEMENT OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO
 *   IDENTIFIED, OR WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT
 *   NEEDED TO COMPLY WITH ANY SUCH LICENSES OR RIGHTS.
 ************************************************************************/

package com.eucalyptus.blockstorage.util;

import java.security.PrivateKey;
import java.security.PublicKey;
import javax.crypto.Cipher;

import org.apache.log4j.Logger;
import org.bouncycastle.util.encoders.Base64;

import com.eucalyptus.blockstorage.Storage;
import com.eucalyptus.bootstrap.Hosts;
import com.eucalyptus.component.ComponentId;
import com.eucalyptus.component.Partition;
import com.eucalyptus.component.Partitions;
import com.eucalyptus.component.ServiceConfigurations;
import com.eucalyptus.component.auth.SystemCredentials;
import com.eucalyptus.component.id.Eucalyptus;
import com.eucalyptus.crypto.Ciphers;
import com.eucalyptus.util.EucalyptusCloudException;

public class BlockStorageUtil {
	private static Logger LOG = Logger.getLogger(BlockStorageUtil.class);
	
	
	/**
	 * Returns the corresponding partition for the requested componentId class running on the local host
	 * @param compClass
	 * @return
	 */
	public static <C extends ComponentId> Partition getPartitionForLocalService(Class<C> compClass) {
		return Partitions.lookup(ServiceConfigurations.lookupByHost(compClass, Hosts.localHost().getDisplayName()));
	}
	
	public static String encryptNodeTargetPassword(String password, Partition partition) throws EucalyptusCloudException {
    try {
      if(partition == null) {
        throw new EucalyptusCloudException("Invalid partition specified. Got null");
      } else {        
        PublicKey ncPublicKey = partition.getNodeCertificate( ).getPublicKey();
        Cipher cipher = Ciphers.RSA_PKCS1.get();
        cipher.init(Cipher.ENCRYPT_MODE, ncPublicKey);
        return new String(Base64.encode(cipher.doFinal(password.getBytes())));
      }
    } catch ( Exception e ) {
			LOG.error( "Unable to encrypt storage target password: " + e.getMessage( ), e );
			throw new EucalyptusCloudException("Unable to encrypt storage target password: " + e.getMessage(), e);
		}
	}

	public static String encryptSCTargetPassword(String password) throws EucalyptusCloudException {
		PublicKey scPublicKey = SystemCredentials.lookup(Storage.class).getKeyPair().getPublic();
		Cipher cipher;
		try {
			cipher = Ciphers.RSA_PKCS1.get();
			cipher.init(Cipher.ENCRYPT_MODE, scPublicKey);
			return new String(Base64.encode(cipher.doFinal(password.getBytes())));	      
		} catch (Exception e) {
			LOG.error("Unable to encrypted storage target password");
			throw new EucalyptusCloudException(e.getMessage(), e);
		}
	}

	public static String decryptSCTargetPassword(String encryptedPassword) throws EucalyptusCloudException {
		PrivateKey scPrivateKey = SystemCredentials.lookup(Storage.class).getPrivateKey();
		try {
			Cipher cipher = Ciphers.RSA_PKCS1.get();
			cipher.init(Cipher.DECRYPT_MODE, scPrivateKey);
			return new String(cipher.doFinal(Base64.decode(encryptedPassword)));
		} catch(Exception ex) {
			LOG.error(ex);
			throw new EucalyptusCloudException("Unable to decrypt storage target password", ex);
		}
	}
	
	//Encrypt data using the node public key
	public static String encryptForNode(String data, Partition partition) throws EucalyptusCloudException {
		try {
			if( partition == null) {
				throw new EucalyptusCloudException("Invalid partition specified. Got null");
			} else {
				PublicKey ncPublicKey = partition.getNodeCertificate( ).getPublicKey();
				Cipher cipher = Ciphers.RSA_PKCS1.get();
				cipher.init(Cipher.ENCRYPT_MODE, ncPublicKey);
				return new String(Base64.encode(cipher.doFinal(data.getBytes())));
			}
		} catch ( Exception e ) {
			LOG.error( "Unable to encrypt data: " + e.getMessage( ), e );
			throw new EucalyptusCloudException("Unable to encrypt data: " + e.getMessage(), e);
		}
	}
	
	//Decrypt data using the node private key. Primarly for VMwareBroker
	public static String decryptForNode(String data, Partition partition) throws EucalyptusCloudException {
		try {
			if( partition == null) {
				throw new EucalyptusCloudException("Invalid partition specified. Got null");
			} else {
				PrivateKey ncPrivateKey = partition.getNodePrivateKey();
				Cipher cipher = Ciphers.RSA_PKCS1.get();
				cipher.init(Cipher.DECRYPT_MODE, ncPrivateKey);
				return new String(cipher.doFinal(Base64.decode(data)));
			}
		} catch ( Exception e ) {
			LOG.error( "Unable to dencrypt data with node private key: " + e.getMessage( ), e );
			throw new EucalyptusCloudException("Unable to encrypt data with node private key: " + e.getMessage(), e);
		}
	}
	
	//Encrypt data using the cloud public key
	public static String encryptForCloud(String data) throws EucalyptusCloudException {
		try {
			PublicKey clcPublicKey = SystemCredentials.lookup(Eucalyptus.class).getCertificate().getPublicKey();
			Cipher cipher = Ciphers.RSA_PKCS1.get();
			cipher.init(Cipher.ENCRYPT_MODE, clcPublicKey);
			return new String(Base64.encode(cipher.doFinal(data.getBytes())));	      
		} catch ( Exception e ) {
			LOG.error( "Unable to encrypt data: " + e.getMessage( ), e );
			throw new EucalyptusCloudException("Unable to encrypt data: " + e.getMessage(), e);
		}
	}
	
	//Decrypt data encrypted with the Cloud public key
	public static String decryptWithCloud(String data) throws EucalyptusCloudException {
		PrivateKey clcPrivateKey = SystemCredentials.lookup(Eucalyptus.class).getPrivateKey();
		try {
			Cipher cipher = Ciphers.RSA_PKCS1.get();
			cipher.init(Cipher.DECRYPT_MODE, clcPrivateKey);
			return new String(cipher.doFinal(Base64.decode(data)));
		} catch(Exception ex) {
			LOG.error(ex);
			throw new EucalyptusCloudException("Unable to decrypt data with cloud private key", ex);
		}
	}
}
