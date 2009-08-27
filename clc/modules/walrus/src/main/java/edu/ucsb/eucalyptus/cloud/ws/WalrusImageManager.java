/*******************************************************************************
*Copyright (c) 2009  Eucalyptus Systems, Inc.
* 
*  This program is free software: you can redistribute it and/or modify
*  it under the terms of the GNU General Public License as published by
*  the Free Software Foundation, only version 3 of the License.
* 
* 
*  This file is distributed in the hope that it will be useful, but WITHOUT
*  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
*  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
*  for more details.
* 
*  You should have received a copy of the GNU General Public License along
*  with this program.  If not, see <http://www.gnu.org/licenses/>.
* 
*  Please contact Eucalyptus Systems, Inc., 130 Castilian
*  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
*  if you need additional information or have any questions.
* 
*  This file may incorporate work covered under the following copyright and
*  permission notice:
* 
*    SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
*    IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
*    BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
*    THE REGENTS’ DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
*    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
*    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
*    ANY SUCH LICENSES OR RIGHTS.
 ******************************************************************************/
package edu.ucsb.eucalyptus.cloud.ws;
/*
 *
 * Author: Sunil Soman sunils@cs.ucsb.edu
 */

import edu.ucsb.eucalyptus.cloud.*;
import edu.ucsb.eucalyptus.cloud.entities.*;
import edu.ucsb.eucalyptus.msgs.*;
import edu.ucsb.eucalyptus.storage.StorageManager;
import edu.ucsb.eucalyptus.util.*;
import org.apache.log4j.Logger;
import org.apache.tools.ant.util.DateUtils;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.stream.ChunkedFile;
import org.jboss.netty.handler.stream.ChunkedInput;

import com.eucalyptus.auth.Credentials;
import com.eucalyptus.auth.Hashes;
import com.eucalyptus.auth.NoSuchUserException;
import com.eucalyptus.auth.SystemCredentialProvider;
import com.eucalyptus.auth.User;
import com.eucalyptus.auth.UserCredentialProvider;
import com.eucalyptus.auth.X509Cert;
import com.eucalyptus.auth.util.EucaKeyStore;
import com.eucalyptus.bootstrap.Component;
import com.eucalyptus.util.EntityWrapper;
import com.eucalyptus.util.EucalyptusCloudException;
import com.eucalyptus.ws.MappingHttpResponse;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.GZIPInputStream;
import com.eucalyptus.util.WalrusProperties;

public class WalrusImageManager {
	private static Logger LOG = Logger.getLogger( WalrusImageManager.class );
	private static ConcurrentHashMap<String, ImageCacher> imageCachers = new ConcurrentHashMap<String, ImageCacher>();

	private StorageManager storageManager;
	private  WalrusDataMessenger imageMessenger;

	public WalrusImageManager(StorageManager storageManager, WalrusDataMessenger imageMessenger) {
		this.storageManager = storageManager;
		this.imageMessenger = imageMessenger;
	}

	private String decryptImage(String bucketName, String objectKey, String userId, boolean isAdministrator) throws EucalyptusCloudException {
		EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
		BucketInfo bucketInfo = new BucketInfo(bucketName);
		List<BucketInfo> bucketList = db.query(bucketInfo);


		if (bucketList.size() > 0) {
			EntityWrapper<ObjectInfo> dbObject = db.recast(ObjectInfo.class);
			ObjectInfo searchObjectInfo = new ObjectInfo(bucketName, objectKey);
			List<ObjectInfo> objectInfos = dbObject.query(searchObjectInfo);
			if(objectInfos.size() > 0)  {
				ObjectInfo objectInfo = objectInfos.get(0);
				if(objectInfo.canRead(userId)) {
					String objectName = objectInfo.getObjectName();
					File file = new File(storageManager.getObjectPath(bucketName, objectName));
					XMLParser parser = new XMLParser(file);
					//Read manifest
					String imageKey = parser.getValue("//image/name");
					String encryptedKey = parser.getValue("//ec2_encrypted_key");
					String encryptedIV = parser.getValue("//ec2_encrypted_iv");
					String signature = parser.getValue("//signature");


					String image = parser.getXML("image");
					String machineConfiguration = parser.getXML("machine_configuration");

					String verificationString = machineConfiguration + image;

					Signature sigVerifier;
					try {
						sigVerifier = Signature.getInstance("SHA1withRSA");
					} catch (NoSuchAlgorithmException ex) {
						LOG.error(ex, ex);
						throw new DecryptionFailedException("SHA1withRSA not found");
					}

					if(isAdministrator) {
						try {
							boolean verified = false;
							List<String> aliases = UserCredentialProvider.getAliases();
							for(String alias : aliases) {
								X509Certificate cert = UserCredentialProvider.getCertificate(alias);
								verified = canVerifySignature(sigVerifier, cert, signature, verificationString);
								if(verified)
									break;
							}
							if(!verified) {
								throw new NotAuthorizedException("Invalid signature");
							}
						} catch (Exception ex) {
							db.rollback();
							LOG.error(ex, ex);
							throw new DecryptionFailedException("signature verification");
						}
					} else {
						boolean signatureVerified = false;
	          User user = null;
	          try {
	            user = UserCredentialProvider.getUser( userId );
	          } catch ( NoSuchUserException e ) {
	            throw new AccessDeniedException(userId,e);            
	          }         
	          for(X509Cert certInfo: user.getCertificates( )) {
							try {
								X509Certificate cert = X509Cert.toCertificate( certInfo );
								signatureVerified = canVerifySignature(sigVerifier, cert, signature, verificationString);
								if (signatureVerified)
									break;
							} catch(Exception ex) {
								db.rollback();
								LOG.error(ex, ex);
								throw new DecryptionFailedException("signature verification");
							}
						}
						if(!signatureVerified) {
							throw new NotAuthorizedException("Invalid signature");
						}
					}
					List<String> parts = parser.getValues("//image/parts/part/filename");
					ArrayList<String> qualifiedPaths = new ArrayList<String>();
					searchObjectInfo = new ObjectInfo();
					searchObjectInfo.setBucketName(bucketName);
					List<ObjectInfo> bucketObjectInfos = dbObject.query(searchObjectInfo);

					for (String part: parts) {
						for(ObjectInfo object : bucketObjectInfos) {
							if(part.equals(object.getObjectKey())) {
								qualifiedPaths.add(storageManager.getObjectPath(bucketName, object.getObjectName()));
							}
						}
					}
					//Assemble parts
					String encryptedImageKey = imageKey + "-" + Hashes.getRandom(5) + ".crypt.gz";
					String encryptedImageName = storageManager.getObjectPath(bucketName, encryptedImageKey);
					String decryptedImageKey = encryptedImageKey.replaceAll("crypt.gz", "tgz");
					String decryptedImageName = storageManager.getObjectPath(bucketName, decryptedImageKey);
					assembleParts(encryptedImageName, qualifiedPaths);
					//Decrypt key and IV

					byte[] key;
					byte[] iv;
					try {
						PrivateKey pk = SystemCredentialProvider.getCredentialProvider(
								Component.eucalyptus ).getPrivateKey();
						Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
						cipher.init(Cipher.DECRYPT_MODE, pk);
						String keyString = new String(cipher.doFinal(Hashes.hexToBytes(encryptedKey)));
						key = Hashes.hexToBytes(keyString);
						String ivString = new String(cipher.doFinal(Hashes.hexToBytes(encryptedIV)));
						iv = Hashes.hexToBytes(ivString);
					} catch(Exception ex) {
						db.rollback();
						LOG.error(ex, ex);
						throw new DecryptionFailedException("AES params");
					}

					//Unencrypt image
					try {
						Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding", "BC");
						IvParameterSpec salt = new IvParameterSpec(iv);
						SecretKey keySpec = new SecretKeySpec(key, "AES");
						cipher.init(Cipher.DECRYPT_MODE, keySpec, salt);
						decryptImage(encryptedImageName, decryptedImageName, cipher);
					} catch (Exception ex) {
						db.rollback();
						LOG.error(ex, ex);
						throw new DecryptionFailedException("decryption failed");
					}
					try {
						storageManager.deleteAbsoluteObject(encryptedImageName);
					} catch (Exception ex) {
						LOG.error(ex);
						throw new EucalyptusCloudException();
					}
					db.commit();
					return decryptedImageKey;
				}
			}
		}
		return null;
	}

	private boolean canVerifySignature(Signature sigVerifier, X509Certificate cert, String signature, String verificationString) throws Exception {
		PublicKey publicKey = cert.getPublicKey();
		sigVerifier.initVerify(publicKey);
		sigVerifier.update((verificationString).getBytes());
		return sigVerifier.verify(Hashes.hexToBytes(signature));
	}


	private void checkManifest(String bucketName, String objectKey, String userId) throws EucalyptusCloudException {
		EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
		BucketInfo bucketInfo = new BucketInfo(bucketName);
		List<BucketInfo> bucketList = db.query(bucketInfo);


		if (bucketList.size() > 0) {
			EntityWrapper<ObjectInfo> dbObject = db.recast(ObjectInfo.class);
			ObjectInfo searchObjectInfo = new ObjectInfo(bucketName, objectKey);
			List<ObjectInfo> objectInfos = dbObject.query(searchObjectInfo);
			if(objectInfos.size() > 0)  {
				ObjectInfo objectInfo = objectInfos.get(0);

				if(objectInfo.canRead(userId)) {
					String objectName = objectInfo.getObjectName();
					File file = new File(storageManager.getObjectPath(bucketName, objectName));
					XMLParser parser = new XMLParser(file);
					//Read manifest
					String encryptedKey = parser.getValue("//ec2_encrypted_key");
					String encryptedIV = parser.getValue("//ec2_encrypted_iv");
					String signature = parser.getValue("//signature");

					String image = parser.getXML("image");
					String machineConfiguration = parser.getXML("machine_configuration");

					User user = null;
          try {
            user = UserCredentialProvider.getUser( userId );
          } catch ( NoSuchUserException e ) {
            throw new AccessDeniedException(userId,e);            
          }         
          boolean signatureVerified = false;

					Signature sigVerifier;
					try {
						sigVerifier = Signature.getInstance("SHA1withRSA");
					} catch (NoSuchAlgorithmException ex) {
						LOG.error(ex, ex);
						throw new DecryptionFailedException("SHA1withRSA not found");
					}

					for(X509Cert certInfo: user.getCertificates( )) {
						try {
							X509Certificate cert = X509Cert.toCertificate( certInfo );
							PublicKey publicKey = cert.getPublicKey();
							sigVerifier.initVerify(publicKey);
							sigVerifier.update((machineConfiguration + image).getBytes());
							signatureVerified = sigVerifier.verify(Hashes.hexToBytes(signature));
							if (signatureVerified) {
								break;
							}
						} catch(Exception ex) {
							db.rollback();
							LOG.error(ex, ex);
							throw new DecryptionFailedException("signature verification");
						}
					}

					if(!signatureVerified) {
						throw new NotAuthorizedException("Invalid signature");
					}
					//Decrypt key and IV

					byte[] key;
					byte[] iv;
					try {
						PrivateKey pk = SystemCredentialProvider.getCredentialProvider(Component.eucalyptus).getPrivateKey();
						Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
						cipher.init(Cipher.DECRYPT_MODE, pk);
						key = Hashes.hexToBytes(new String(cipher.doFinal(Hashes.hexToBytes(encryptedKey))));
						iv = Hashes.hexToBytes(new String(cipher.doFinal(Hashes.hexToBytes(encryptedIV))));
					} catch(Exception ex) {
						db.rollback();
						LOG.error(ex, ex);
						throw new DecryptionFailedException("AES params");
					}
					db.commit();
				} else {
					db.rollback();
					throw new AccessDeniedException(objectKey);
				}
			} else {
				db.rollback();
				throw new NoSuchEntityException(objectKey);
			}
		} else {
			db.rollback();
			throw new NoSuchBucketException(bucketName);
		}
	}

	private boolean isCached(String bucketName, String manifestKey) {
		EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
		ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
		try {
			ImageCacheInfo foundImageCacheInfo = db.getUnique(searchImageCacheInfo);
			if(foundImageCacheInfo.getInCache())
				return true;
			else
				return false;
		} catch(Exception ex) {
			return false;
		} finally {
			db.commit();
		}
	}

	private long checkCachingProgress(String bucketName, String manifestKey, long oldBytesRead) {
		EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
		ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
		try {
			ImageCacheInfo foundImageCacheInfo = db.getUnique(searchImageCacheInfo);
			String cacheImageKey = foundImageCacheInfo.getImageName().replaceAll(".tgz", "");
			long objectSize = storageManager.getObjectSize(bucketName, cacheImageKey);
			if(objectSize > 0) {
				return objectSize - oldBytesRead;
			}
			return oldBytesRead;
		} catch (Exception ex) {
			return oldBytesRead;
		} finally {
			db.commit();
		}
	}

	private synchronized void cacheImage(String bucketName, String manifestKey, String userId, boolean isAdministrator) throws EucalyptusCloudException {

		EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
		ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
		List<ImageCacheInfo> imageCacheInfos = db.query(searchImageCacheInfo);
		String decryptedImageKey = null;
		if(imageCacheInfos.size() != 0) {
			ImageCacheInfo icInfo = imageCacheInfos.get(0);
			if(!icInfo.getInCache()) {
				decryptedImageKey = icInfo.getImageName();
			} else {
				db.commit();
				return;
			}
		}
		//unzip, untar image in the background
		ImageCacher imageCacher = imageCachers.putIfAbsent(bucketName + manifestKey, new ImageCacher(bucketName, manifestKey, decryptedImageKey));
		if(imageCacher == null) {
			if(decryptedImageKey == null) {
				decryptedImageKey = decryptImage(bucketName, manifestKey, userId, isAdministrator);
				//decryption worked. Add it.
				ImageCacheInfo foundImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
				foundImageCacheInfo.setImageName(decryptedImageKey);
				foundImageCacheInfo.setInCache(false);
				foundImageCacheInfo.setUseCount(0);
				foundImageCacheInfo.setSize(0L);
				db.add(foundImageCacheInfo);
			}
			db.commit();
			imageCacher = imageCachers.get(bucketName + manifestKey);
			imageCacher.setDecryptedImageKey(decryptedImageKey);
			imageCacher.start();
		} else {
			db.commit();
		}
	}

	private void flushCachedImage (String bucketName, String objectKey) throws Exception {
		WalrusSemaphore semaphore = imageMessenger.getSemaphore(bucketName + "/" + objectKey);
		while(semaphore.inUse()) {
			try {
				synchronized (semaphore) {
					semaphore.wait();
				}
			} catch(InterruptedException ex) {
				LOG.error(ex);
			}
		}
		imageMessenger.removeSemaphore(bucketName + "/" + objectKey);
		EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
		ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, objectKey);
		List<ImageCacheInfo> foundImageCacheInfos = db.query(searchImageCacheInfo);

		if(foundImageCacheInfos.size() > 0) {
			ImageCacheInfo foundImageCacheInfo = foundImageCacheInfos.get(0);
			LOG.info("Attempting to flush cached image: " + bucketName + "/" + objectKey);
			if(foundImageCacheInfo.getInCache() && (imageCachers.get(bucketName + objectKey) == null)) {
				db.delete(foundImageCacheInfo);
				storageManager.deleteObject(bucketName, foundImageCacheInfo.getImageName());
			}
			db.commit();
		} else {
			db.rollback();
			LOG.warn("Cannot find image in cache" + bucketName + "/" + objectKey);
		}
	}

	public void startImageCacheFlusher(String bucketName, String manifestName) {
		ImageCacheFlusher imageCacheFlusher = new ImageCacheFlusher(bucketName, manifestName);
		imageCacheFlusher.start();
	}

	private class ImageCacheFlusher extends Thread {
		private String bucketName;
		private String objectKey;

		public ImageCacheFlusher(String bucketName, String objectKey) {
			this.bucketName = bucketName;
			this.objectKey = objectKey;
		}

		public void run() {
			try {
				flushCachedImage(bucketName, objectKey);
			} catch(Exception ex) {
				LOG.error(ex);
			}
		}
	}


	private class ImageCacher extends Thread {

		private String bucketName;
		private String manifestKey;
		private String decryptedImageKey;
		private boolean imageSizeExceeded;
		private long spaceNeeded;

		public ImageCacher(String bucketName, String manifestKey, String decryptedImageKey) {
			this.bucketName = bucketName;
			this.manifestKey = manifestKey;
			this.decryptedImageKey = decryptedImageKey;
			this.imageSizeExceeded = false;

		}

		public void setDecryptedImageKey(String key) {
			this.decryptedImageKey = key;
		}

		private long tryToCache(String decryptedImageName, String tarredImageName, String imageName) {
			Long unencryptedSize = 0L;
			boolean failed = false;
			try {
				if(!imageSizeExceeded) {
					LOG.info("Unzipping image: " + bucketName + "/" + manifestKey);
					unzipImage(decryptedImageName, tarredImageName);
					LOG.info("Untarring image: " + bucketName + "/" + manifestKey);
					unencryptedSize = untarImage(tarredImageName, imageName);
				} else {
					File imageFile = new File(imageName);
					if(imageFile.exists()) {
						unencryptedSize = imageFile.length();
					} else {
						LOG.error("Could not find image: " + imageName);
						imageSizeExceeded = false;
						return -1L;
					}

				}
				Long oldCacheSize = 0L;
				EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
				List<ImageCacheInfo> imageCacheInfos = db.query(new ImageCacheInfo());
				for(ImageCacheInfo imageCacheInfo: imageCacheInfos) {
					if(imageCacheInfo.getInCache()) {
						oldCacheSize += imageCacheInfo.getSize();
					}
				}
				db.commit();
				if((oldCacheSize + unencryptedSize) > WalrusProperties.IMAGE_CACHE_SIZE) {
					LOG.error("Maximum image cache size exceeded when decrypting " + bucketName + "/" + manifestKey);
					failed = true;
					imageSizeExceeded = true;
					spaceNeeded = unencryptedSize;
				}
			} catch(Exception ex) {
				LOG.warn(ex);
				//try to evict an entry and try again
				failed = true;
			}
			if(failed) {
				if(!imageSizeExceeded) {
					try {
						storageManager.deleteAbsoluteObject(tarredImageName);
						storageManager.deleteAbsoluteObject(imageName);
					} catch (Exception exception) {
						LOG.error(exception);
					}
				}
				return -1L;
			}
			LOG.info("Cached image: " + bucketName + "/" + manifestKey + " size: " + String.valueOf(unencryptedSize));
			return unencryptedSize;
		}

		private void notifyWaiters() {
			WalrusMonitor monitor = imageMessenger.getMonitor(bucketName + "/" + manifestKey);
			synchronized (monitor) {
				monitor.notifyAll();
			}
			imageMessenger.removeMonitor(bucketName + "/" + manifestKey);
			imageCachers.remove(bucketName + manifestKey);
		}

		public void run() {
			//update status
			//wake up any waiting consumers
			String decryptedImageName = storageManager.getObjectPath(bucketName, decryptedImageKey);
			String tarredImageName = decryptedImageName.replaceAll("tgz", "tar");
			String imageName = tarredImageName.replaceAll(".tar", "");
			String imageKey = decryptedImageKey.replaceAll(".tgz", "");
			Long unencryptedSize;
			int numberOfRetries = 0;
			while((unencryptedSize = tryToCache(decryptedImageName, tarredImageName, imageName)) < 0) {
				try {
					Thread.sleep(WalrusProperties.IMAGE_CACHE_RETRY_TIMEOUT);
				} catch(InterruptedException ex) {
					notifyWaiters();
					return;
				}
				WalrusProperties.IMAGE_CACHE_RETRY_TIMEOUT = 2* WalrusProperties.IMAGE_CACHE_RETRY_TIMEOUT;
				if(numberOfRetries++ >= WalrusProperties.IMAGE_CACHE_RETRY_LIMIT) {
					notifyWaiters();
					return;
				}
				EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
				ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo();
				searchImageCacheInfo.setInCache(true);
				List<ImageCacheInfo> imageCacheInfos = db.query(searchImageCacheInfo);
				if(imageCacheInfos.size() == 0) {
					LOG.error("No cached images found to flush. Unable to cache image. Please check the error log and the image cache size.");
					db.rollback();
					notifyWaiters();
					return;
				}
				Collections.sort(imageCacheInfos);
				db.commit();
				try {
					if(spaceNeeded > 0) {
						ArrayList<ImageCacheInfo> imagesToFlush = new ArrayList<ImageCacheInfo>();
						long tryToFree = spaceNeeded;
						for(ImageCacheInfo imageCacheInfo : imageCacheInfos) {
							if(tryToFree <= 0)
								break;
							long imageSize = imageCacheInfo.getSize();
							tryToFree -= imageSize;
							imagesToFlush.add(imageCacheInfo);
						}
						if(imagesToFlush.size() == 0) {
							LOG.error("Unable to flush existing images. Sorry.");
							notifyWaiters();
							return;
						}
						for(ImageCacheInfo imageCacheInfo : imagesToFlush) {
							flushCachedImage(imageCacheInfo.getBucketName(), imageCacheInfo.getManifestName());
						}
					} else {
						LOG.error("Unable to cache image. Unable to flush existing images.");
						notifyWaiters();
						return;
					}
				} catch(Exception ex) {
					LOG.error(ex);
					LOG.error("Unable to flush previously cached image. Please increase your image cache size");
					notifyWaiters();
				}
			}
			try {
				storageManager.deleteAbsoluteObject(decryptedImageName);
				storageManager.deleteAbsoluteObject(tarredImageName);

				EntityWrapper<ImageCacheInfo>db = new EntityWrapper<ImageCacheInfo>();
				ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
				List<ImageCacheInfo> foundImageCacheInfos = db.query(searchImageCacheInfo);
				if(foundImageCacheInfos.size() > 0) {
					ImageCacheInfo foundImageCacheInfo = foundImageCacheInfos.get(0);
					foundImageCacheInfo.setImageName(imageKey);
					foundImageCacheInfo.setInCache(true);
					foundImageCacheInfo.setSize(unencryptedSize);
					db.commit();
					//wake up waiters
					notifyWaiters();
				} else {
					db.rollback();
					LOG.error("Could not expand image" + decryptedImageName);
				}
			} catch (Exception ex) {
				LOG.error(ex);
			}
		}
	}

	private void unzipImage(String decryptedImageName, String tarredImageName) throws Exception {
		GZIPInputStream in = new GZIPInputStream(new FileInputStream(new File(decryptedImageName)));
		File outFile = new File(tarredImageName);
		ReadableByteChannel inChannel = Channels.newChannel(in);
		WritableByteChannel outChannel = new FileOutputStream(outFile).getChannel();

		ByteBuffer buffer = ByteBuffer.allocate(102400/*TODO: NEIL WalrusQueryDispatcher.DATA_MESSAGE_SIZE*/);
		while (inChannel.read(buffer) != -1) {
			buffer.flip();
			outChannel.write(buffer);
			buffer.clear();
		}
		outChannel.close();
		inChannel.close();
	}

	private long untarImage(String tarredImageName, String imageName) throws Exception {
		/*TarInputStream in = new TarInputStream(new FileInputStream(new File(tarredImageName)));
       File outFile = new File(imageName);
       BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile));

       TarEntry tEntry = in.getNextEntry();
       assert(!tEntry.isDirectory());

       in.copyEntryContents(out);
       out.close();
       in.close();
       return outFile.length();*/

		//Workaround because TarInputStream is broken
		Tar tarrer = new Tar();
		tarrer.untar(tarredImageName, imageName);
		File outFile = new File(imageName);
		if(outFile.exists())
			return outFile.length();
		else
			throw new EucalyptusCloudException("Could not untar image " + imageName);
	}

	private class StreamConsumer extends Thread
	{
		private InputStream is;
		private File file;

		public StreamConsumer(InputStream is) {
			this.is = is;
		}

		public StreamConsumer(InputStream is, File file) {
			this(is);
			this.file = file;
		}

		public void run()
		{
			try
			{
				BufferedInputStream inStream = new BufferedInputStream(is);
				BufferedOutputStream outStream = null;
				if(file != null) {
					outStream = new BufferedOutputStream(new FileOutputStream(file));
				}
				byte[] bytes = new byte[WalrusProperties.IO_CHUNK_SIZE];
				int bytesRead;
				while((bytesRead = inStream.read(bytes)) > 0) {
					if(outStream != null) {
						outStream.write(bytes, 0, bytesRead);
					}
				}
				if(outStream != null)
					outStream.close();
			} catch (IOException ex)
			{
				LOG.error(ex);
			}
		}
	}

	private class Tar {
		public void untar(String tarFile, String outFile) {
			try
			{
				Runtime rt = Runtime.getRuntime();
				Process proc = rt.exec(new String[]{ "/bin/tar", "xfO", tarFile});
				StreamConsumer error = new StreamConsumer(proc.getErrorStream());
				StreamConsumer output = new StreamConsumer(proc.getInputStream(), new File(outFile));
				error.start();
				output.start();
				int exitVal = proc.waitFor();
				output.join();
			} catch (Throwable t) {
				LOG.error(t);
			}
		}
	}

	private void decryptImage(final String encryptedImageName, final String decryptedImageName, final Cipher cipher) throws Exception {
		LOG.info("Decrypting image: " + decryptedImageName);
		BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(new File(decryptedImageName)));
		File inFile = new File(encryptedImageName);
		BufferedInputStream in = new BufferedInputStream(new FileInputStream(inFile));

		int bytesRead = 0;
		byte[] bytes = new byte[8192];

		while((bytesRead = in.read(bytes)) > 0) {
			byte[] outBytes = cipher.update(bytes, 0, bytesRead);
			out.write(outBytes);
		}
		byte[] outBytes = cipher.doFinal();
		out.write(outBytes);
		in.close();
		out.close();
		LOG.info("Done decrypting: " + decryptedImageName);
	}

	private void assembleParts(final String name, List<String> parts) {
		try {
			FileChannel out = new FileOutputStream(new File(name)).getChannel();
			for (String partName: parts) {
				FileChannel in = new FileInputStream(new File(partName)).getChannel();
				in.transferTo(0, in.size(), out);
				in.close();
			}
			out.close();
		} catch (Exception ex) {
			LOG.error(ex);
		}
	}



	public GetDecryptedImageResponseType getDecryptedImage(GetDecryptedImageType request) throws EucalyptusCloudException {
		GetDecryptedImageResponseType reply = (GetDecryptedImageResponseType) request.getReply();
		String bucketName = request.getBucket();
		String objectKey = request.getKey();
		String userId = request.getUserId();

		EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
		BucketInfo bucketInfo = new BucketInfo(bucketName);
		List<BucketInfo> bucketList = db.query(bucketInfo);
		if (bucketList.size() > 0) {
			EntityWrapper<ObjectInfo> dbObject = db.recast(ObjectInfo.class);
			ObjectInfo searchObjectInfo = new ObjectInfo(bucketName, objectKey);
			List<ObjectInfo> objectInfos = dbObject.query(searchObjectInfo);
			if(objectInfos.size() > 0)  {
				ObjectInfo objectInfo = objectInfos.get(0);

				if(objectInfo.canRead(userId) || request.isAdministrator() ) {
					WalrusSemaphore semaphore = imageMessenger.getSemaphore(bucketName + "/" + objectKey);
					try {
						semaphore.acquire();
					} catch(InterruptedException ex) {
						throw new EucalyptusCloudException("semaphore could not be acquired");
					}
					EntityWrapper<ImageCacheInfo> db2 = new EntityWrapper<ImageCacheInfo>();
					ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, objectKey);
					List<ImageCacheInfo> foundImageCacheInfos = db2.query(searchImageCacheInfo);

					if((foundImageCacheInfos.size() == 0) || (!imageCachers.containsKey(bucketName + objectKey))) {
						db2.commit();
						//issue a cache request
						cacheImage(bucketName, objectKey, userId, request.isAdministrator());
						//query db again
						db2 = new EntityWrapper<ImageCacheInfo>();
						foundImageCacheInfos = db2.query(searchImageCacheInfo);
					}
					ImageCacheInfo foundImageCacheInfo = null;
					if(foundImageCacheInfos.size() > 0)
						foundImageCacheInfo = foundImageCacheInfos.get(0);
					if((foundImageCacheInfo == null) || (!foundImageCacheInfo.getInCache())) {
						boolean cached = false;
						WalrusMonitor monitor = imageMessenger.getMonitor(bucketName + "/" + objectKey);
						synchronized (monitor) {
							try {
								long bytesCached = 0;
								int number_of_tries = 0;
								do {
									monitor.wait(WalrusProperties.CACHE_PROGRESS_TIMEOUT);
									if(isCached(bucketName, objectKey)) {
										cached = true;
										break;
									}
									long newBytesCached = checkCachingProgress(bucketName, objectKey, bytesCached);
									boolean is_caching = (newBytesCached - bytesCached) > 0 ? true : false;

									if (!is_caching && (number_of_tries++ >= WalrusProperties.IMAGE_CACHE_RETRY_LIMIT))
										break;

									bytesCached = newBytesCached;
									if(is_caching) {
										LOG.info("Bytes cached so far for image " + bucketName + "/" + objectKey + " :" +  String.valueOf(bytesCached));
									}
								} while(true);
							} catch(Exception ex) {
								LOG.error(ex);
								db2.rollback();
								db.rollback();
								throw new EucalyptusCloudException("monitor failure");
							}
						}
						if(!cached) {
							LOG.error("Tired of waiting to cache image: " + bucketName + "/" + objectKey + " giving up");
							db2.rollback();
							db.rollback();
							throw new EucalyptusCloudException("caching failure");
						}
						//caching may have modified the db. repeat the query
						db2.commit();
						db2 = new EntityWrapper<ImageCacheInfo>();
						foundImageCacheInfos = db2.query(searchImageCacheInfo);
						if(foundImageCacheInfos.size() > 0) {
							foundImageCacheInfo = foundImageCacheInfos.get(0);
							foundImageCacheInfo.setUseCount(foundImageCacheInfo.getUseCount() + 1);
							assert(foundImageCacheInfo.getInCache());
						} else {
							db.rollback();
							db2.rollback();
							throw new NoSuchEntityException(objectKey);
						}
					}

					Long unencryptedSize = foundImageCacheInfo.getSize();
					String imageKey = foundImageCacheInfo.getImageName();
					reply.setSize(unencryptedSize);
					reply.setLastModified(DateUtils.format(objectInfo.getLastModified().getTime(), DateUtils.ISO8601_DATETIME_PATTERN) + ".000Z");
					reply.setEtag("");
					MappingHttpResponse httpResponse = new MappingHttpResponse( HttpVersion.HTTP_1_1 ); 
					storageManager.sendObject(request.getChannel(), httpResponse, bucketName, imageKey, unencryptedSize, null, 
							DateUtils.format(objectInfo.getLastModified().getTime(), DateUtils.ISO8601_DATETIME_PATTERN + ".000Z"), 
							objectInfo.getContentType(), objectInfo.getContentDisposition(), request.getIsCompressed());                            
					db.commit();
					db2.commit();
					return reply;
				} else {
					db.rollback();
					throw new AccessDeniedException(objectKey);
				}

			} else {
				db.rollback();
				throw new NoSuchEntityException(objectKey);
			}
		} else {
			db.rollback();
			throw new NoSuchBucketException(bucketName);
		}
	}

	public CheckImageResponseType checkImage(CheckImageType request) throws EucalyptusCloudException {
		CheckImageResponseType reply = (CheckImageResponseType) request.getReply();
		reply.setSuccess(false);
		String bucketName = request.getBucket();
		String objectKey = request.getKey();
		String userId = request.getUserId();

		EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
		BucketInfo bucketInfo = new BucketInfo(bucketName);
		List<BucketInfo> bucketList = db.query(bucketInfo);


		if (bucketList.size() > 0) {
			EntityWrapper<ObjectInfo> dbObject = db.recast(ObjectInfo.class);
			ObjectInfo searchObjectInfo = new ObjectInfo(bucketName, objectKey);
			List<ObjectInfo> objectInfos = dbObject.query(searchObjectInfo);
			if(objectInfos.size() > 0)  {
				ObjectInfo objectInfo = objectInfos.get(0);
				if(objectInfo.canRead(userId)) {
					checkManifest(bucketName, objectKey, userId);
					reply.setSuccess(true);
					db.commit();
					return reply;
				} else {
					db.rollback();
					throw new AccessDeniedException(objectKey);
				}
			} else {
				db.rollback();
				throw new NoSuchEntityException(objectKey);
			}
		} else {
			db.rollback();
			throw new NoSuchBucketException(bucketName);
		}
	}

	public CacheImageResponseType cacheImage(CacheImageType request) throws EucalyptusCloudException {
		CacheImageResponseType reply = (CacheImageResponseType) request.getReply();
		reply.setSuccess(false);
		String bucketName = request.getBucket();
		String manifestKey = request.getKey();
		String userId = request.getUserId();


		EntityWrapper<BucketInfo> db = new EntityWrapper<BucketInfo>();
		BucketInfo bucketInfo = new BucketInfo(bucketName);
		List<BucketInfo> bucketList = db.query(bucketInfo);

		if (bucketList.size() > 0) {
			EntityWrapper<ObjectInfo> dbObject = db.recast(ObjectInfo.class);
			ObjectInfo searchObjectInfo = new ObjectInfo(bucketName, manifestKey);
			List<ObjectInfo> objectInfos = dbObject.query(searchObjectInfo);
			if(objectInfos.size() > 0)  {
				ObjectInfo objectInfo = objectInfos.get(0);

				if(objectInfo.canRead(userId)) {
					EntityWrapper<ImageCacheInfo> db2 = new EntityWrapper<ImageCacheInfo>();
					ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
					List<ImageCacheInfo> foundImageCacheInfos = db2.query(searchImageCacheInfo);
					db2.commit();
					if((foundImageCacheInfos.size() == 0) || (!imageCachers.containsKey(bucketName + manifestKey))) {
						cacheImage(bucketName, manifestKey, userId, request.isAdministrator());
						reply.setSuccess(true);
					}
					return reply;
				} else {
					db.rollback();
					throw new AccessDeniedException(manifestKey);
				}

			} else {
				throw new NoSuchEntityException(manifestKey);

			}
		} else {
			throw new NoSuchBucketException(bucketName);
		}
	}

	public FlushCachedImageResponseType flushCachedImage(FlushCachedImageType request) throws EucalyptusCloudException {
		FlushCachedImageResponseType reply = (FlushCachedImageResponseType) request.getReply();

		String bucketName = request.getBucket();
		String manifestKey = request.getKey();

		EntityWrapper<ImageCacheInfo> db = new EntityWrapper<ImageCacheInfo>();
		ImageCacheInfo searchImageCacheInfo = new ImageCacheInfo(bucketName, manifestKey);
		List<ImageCacheInfo> foundImageCacheInfos = db.query(searchImageCacheInfo);

		if(foundImageCacheInfos.size() > 0) {
			ImageCacheInfo foundImageCacheInfo = foundImageCacheInfos.get(0);
			if(foundImageCacheInfo.getInCache() && (imageCachers.get(bucketName + manifestKey) == null)) {
				//check that there are no operations in progress and then flush cache and delete image file
				db.commit();
				ImageCacheFlusher imageCacheFlusher = new ImageCacheFlusher(bucketName, manifestKey);
				imageCacheFlusher.start();
			} else {
				db.rollback();
				throw new EucalyptusCloudException("not in cache");
			}
		} else {
			db.rollback();
			throw new NoSuchEntityException(bucketName + manifestKey);
		}
		return reply;
	}



}
