package org.apache.lucene.facet.taxonomy.directory;

import java.io.IOException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.lucene.document.Document;
import org.apache.lucene.facet.collections.LRUHashMap;
import org.apache.lucene.facet.taxonomy.CategoryPath;
import org.apache.lucene.facet.taxonomy.ParallelTaxonomyArrays;
import org.apache.lucene.facet.taxonomy.TaxonomyReader;
import org.apache.lucene.index.CorruptIndexException; // javadocs
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocsEnum;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.MultiFields;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * A {@link TaxonomyReader} which retrieves stored taxonomy information from a
 * {@link Directory}.
 * <P>
 * Reading from the on-disk index on every method call is too slow, so this
 * implementation employs caching: Some methods cache recent requests and their
 * results, while other methods prefetch all the data into memory and then
 * provide answers directly from in-memory tables. See the documentation of
 * individual methods for comments on their performance.
 * 
 * @lucene.experimental
 */
public class DirectoryTaxonomyReader extends TaxonomyReader {

  private static final Logger logger = Logger.getLogger(DirectoryTaxonomyReader.class.getName());

  private static final int DEFAULT_CACHE_VALUE = 4000;
  
  private final DirectoryTaxonomyWriter taxoWriter;
  private final long taxoEpoch; // used in doOpenIfChanged 
  private final DirectoryReader indexReader;

  // TODO: test DoubleBarrelLRUCache and consider using it instead
  private LRUHashMap<CategoryPath, Integer> ordinalCache;
  private LRUHashMap<Integer, CategoryPath> categoryCache;

  private volatile TaxonomyIndexArrays taxoArrays;

  private char delimiter = Consts.DEFAULT_DELIMITER;

  /**
   * Called only from {@link #doOpenIfChanged()}. If the taxonomy has been
   * recreated, you should pass {@code null} as the caches and parent/children
   * arrays.
   */
  DirectoryTaxonomyReader(DirectoryReader indexReader, DirectoryTaxonomyWriter taxoWriter,
      LRUHashMap<CategoryPath,Integer> ordinalCache, LRUHashMap<Integer,CategoryPath> categoryCache,
      TaxonomyIndexArrays taxoArrays) throws IOException {
    this.indexReader = indexReader;
    this.taxoWriter = taxoWriter;
    this.taxoEpoch = taxoWriter == null ? -1 : taxoWriter.getTaxonomyEpoch();
    
    // use the same instance of the cache, note the protective code in getOrdinal and getPath
    this.ordinalCache = ordinalCache == null ? new LRUHashMap<CategoryPath,Integer>(DEFAULT_CACHE_VALUE) : ordinalCache;
    this.categoryCache = categoryCache == null ? new LRUHashMap<Integer,CategoryPath>(DEFAULT_CACHE_VALUE) : categoryCache;
    
    this.taxoArrays = taxoArrays != null ? new TaxonomyIndexArrays(indexReader, taxoArrays) : null;
  }
  
  /**
   * Open for reading a taxonomy stored in a given {@link Directory}.
   * 
   * @param directory
   *          The {@link Directory} in which the taxonomy resides.
   * @throws CorruptIndexException
   *           if the Taxonomy is corrupt.
   * @throws IOException
   *           if another error occurred.
   */
  public DirectoryTaxonomyReader(Directory directory) throws IOException {
    indexReader = openIndexReader(directory);
    taxoWriter = null;
    taxoEpoch = -1;

    // These are the default cache sizes; they can be configured after
    // construction with the cache's setMaxSize() method
    ordinalCache = new LRUHashMap<CategoryPath, Integer>(DEFAULT_CACHE_VALUE);
    categoryCache = new LRUHashMap<Integer, CategoryPath>(DEFAULT_CACHE_VALUE);
  }
  
  /**
   * Opens a {@link DirectoryTaxonomyReader} over the given
   * {@link DirectoryTaxonomyWriter} (for NRT).
   * 
   * @param taxoWriter
   *          The {@link DirectoryTaxonomyWriter} from which to obtain newly
   *          added categories, in real-time.
   */
  public DirectoryTaxonomyReader(DirectoryTaxonomyWriter taxoWriter) throws IOException {
    this.taxoWriter = taxoWriter;
    taxoEpoch = taxoWriter.getTaxonomyEpoch();
    indexReader = openIndexReader(taxoWriter.getInternalIndexWriter());
    
    // These are the default cache sizes; they can be configured after
    // construction with the cache's setMaxSize() method
    ordinalCache = new LRUHashMap<CategoryPath, Integer>(DEFAULT_CACHE_VALUE);
    categoryCache = new LRUHashMap<Integer, CategoryPath>(DEFAULT_CACHE_VALUE);
  }
  
  private synchronized void initTaxoArrays() throws IOException {
    if (taxoArrays == null) {
      // according to Java Concurrency in Practice, this might perform better on
      // some JVMs, because the array initialization doesn't happen on the
      // volatile member.
      TaxonomyIndexArrays tmpArrays = new TaxonomyIndexArrays(indexReader);
      taxoArrays = tmpArrays;
    }
  }
  
  @Override
  protected void doClose() throws IOException {
    indexReader.close();
    taxoArrays = null;
    // do not clear() the caches, as they may be used by other DTR instances.
    ordinalCache = null;
    categoryCache = null;
  }
  
  /**
   * Implements the opening of a new {@link DirectoryTaxonomyReader} instance if
   * the taxonomy has changed.
   * 
   * <p>
   * <b>NOTE:</b> the returned {@link DirectoryTaxonomyReader} shares the
   * ordinal and category caches with this reader. This is not expected to cause
   * any issues, unless the two instances continue to live. The reader
   * guarantees that the two instances cannot affect each other in terms of
   * correctness of the caches, however if the size of the cache is changed
   * through {@link #setCacheSize(int)}, it will affect both reader instances.
   */
  @Override
  protected DirectoryTaxonomyReader doOpenIfChanged() throws IOException {
    ensureOpen();
    
    // This works for both NRT and non-NRT readers (i.e. an NRT reader remains NRT).
    final DirectoryReader r2 = DirectoryReader.openIfChanged(indexReader);
    if (r2 == null) {
      return null; // no changes, nothing to do
    }

    // check if the taxonomy was recreated
    boolean success = false;
    try {
      boolean recreated = false;
      if (taxoWriter == null) {
        // not NRT, check epoch from commit data
        String t1 = indexReader.getIndexCommit().getUserData().get(DirectoryTaxonomyWriter.INDEX_EPOCH);
        String t2 = r2.getIndexCommit().getUserData().get(DirectoryTaxonomyWriter.INDEX_EPOCH);
        if (t1 == null) {
          if (t2 != null) {
            recreated = true;
          }
        } else if (!t1.equals(t2)) {
          // t1 != null and t2 cannot be null b/c DirTaxoWriter always puts the commit data.
          // it's ok to use String.equals because we require the two epoch values to be the same.
          recreated = true;
        }
      } else {
        // NRT, compare current taxoWriter.epoch() vs the one that was given at construction
        if (taxoEpoch != taxoWriter.getTaxonomyEpoch()) {
          recreated = true;
        }
      }

      final DirectoryTaxonomyReader newtr;
      if (recreated) {
        // if recreated, do not reuse anything from this instace. the information
        // will be lazily computed by the new instance when needed.
        newtr = new DirectoryTaxonomyReader(r2, taxoWriter, null, null, null);
      } else {
        newtr = new DirectoryTaxonomyReader(r2, taxoWriter, ordinalCache, categoryCache, taxoArrays);
      }
      
      success = true;
      return newtr;
    } finally {
      if (!success) {
        IOUtils.closeWhileHandlingException(r2);
      }
    }
  }

  protected DirectoryReader openIndexReader(Directory directory) throws IOException {
    return DirectoryReader.open(directory);
  }

  protected DirectoryReader openIndexReader(IndexWriter writer) throws IOException {
    return DirectoryReader.open(writer, false);
  }

  /**
   * Expert: returns the underlying {@link DirectoryReader} instance that is
   * used by this {@link TaxonomyReader}.
   */
  DirectoryReader getInternalIndexReader() {
    ensureOpen();
    return indexReader;
  }

  @Override
  public ParallelTaxonomyArrays getParallelTaxonomyArrays() throws IOException {
    ensureOpen();
    if (taxoArrays == null) {
      initTaxoArrays();
    }
    return taxoArrays;
  }

  @Override
  public Map<String, String> getCommitUserData() throws IOException {
    ensureOpen();
    return indexReader.getIndexCommit().getUserData();
  }

  @Override
  public int getOrdinal(CategoryPath cp) throws IOException {
    ensureOpen();
    if (cp.length == 0) {
      return ROOT_ORDINAL;
    }

    // First try to find the answer in the LRU cache:
    synchronized (ordinalCache) {
      Integer res = ordinalCache.get(cp);
      if (res != null) {
        if (res.intValue() < indexReader.maxDoc()) {
          // Since the cache is shared with DTR instances allocated from
          // doOpenIfChanged, we need to ensure that the ordinal is one that
          // this DTR instance recognizes.
          return res.intValue();
        } else {
          // if we get here, it means that the category was found in the cache,
          // but is not recognized by this TR instance. Therefore there's no
          // need to continue search for the path on disk, because we won't find
          // it there too.
          return TaxonomyReader.INVALID_ORDINAL;
        }
      }
    }

    // If we're still here, we have a cache miss. We need to fetch the
    // value from disk, and then also put it in the cache:
    int ret = TaxonomyReader.INVALID_ORDINAL;
    DocsEnum docs = MultiFields.getTermDocsEnum(indexReader, null, Consts.FULL, new BytesRef(cp.toString(delimiter)), 0);
    if (docs != null && docs.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
      ret = docs.docID();
      
      // we only store the fact that a category exists, not its inexistence.
      // This is required because the caches are shared with new DTR instances
      // that are allocated from doOpenIfChanged. Therefore, if we only store
      // information about found categories, we cannot accidently tell a new
      // generation of DTR that a category does not exist.
      synchronized (ordinalCache) {
        ordinalCache.put(cp, Integer.valueOf(ret));
      }
    }

    return ret;
  }

  @Override
  public CategoryPath getPath(int ordinal) throws IOException {
    ensureOpen();
    
    // Since the cache is shared with DTR instances allocated from
    // doOpenIfChanged, we need to ensure that the ordinal is one that this DTR
    // instance recognizes. Therefore we do this check up front, before we hit
    // the cache.
    if (ordinal < 0 || ordinal >= indexReader.maxDoc()) {
      return null;
    }
    
    // TODO: can we use an int-based hash impl, such as IntToObjectMap,
    // wrapped as LRU?
    Integer catIDInteger = Integer.valueOf(ordinal);
    synchronized (categoryCache) {
      CategoryPath res = categoryCache.get(catIDInteger);
      if (res != null) {
        return res;
      }
    }
    
    Document doc = indexReader.document(ordinal);
    CategoryPath ret = new CategoryPath(doc.get(Consts.FULL), delimiter);
    synchronized (categoryCache) {
      categoryCache.put(catIDInteger, ret);
    }
    
    return ret;
  }

  @Override
  public int getSize() {
    ensureOpen();
    return indexReader.numDocs();
  }
  
  /**
   * setCacheSize controls the maximum allowed size of each of the caches
   * used by {@link #getPath(int)} and {@link #getOrdinal(CategoryPath)}.
   * <P>
   * Currently, if the given size is smaller than the current size of
   * a cache, it will not shrink, and rather we be limited to its current
   * size.
   * @param size the new maximum cache size, in number of entries.
   */
  public void setCacheSize(int size) {
    ensureOpen();
    synchronized (categoryCache) {
      categoryCache.setMaxSize(size);
    }
    synchronized (ordinalCache) {
      ordinalCache.setMaxSize(size);
    }
  }

  /**
   * setDelimiter changes the character that the taxonomy uses in its
   * internal storage as a delimiter between category components. Do not
   * use this method unless you really know what you are doing.
   * <P>
   * If you do use this method, make sure you call it before any other
   * methods that actually queries the taxonomy. Moreover, make sure you
   * always pass the same delimiter for all LuceneTaxonomyWriter and
   * LuceneTaxonomyReader objects you create.
   */
  public void setDelimiter(char delimiter) {
    ensureOpen();
    this.delimiter = delimiter;
  }
  
  public String toString(int max) {
    ensureOpen();
    StringBuilder sb = new StringBuilder();
    int upperl = Math.min(max, indexReader.maxDoc());
    for (int i = 0; i < upperl; i++) {
      try {
        CategoryPath category = this.getPath(i);
        if (category == null) {
          sb.append(i + ": NULL!! \n");
          continue;
        } 
        if (category.length == 0) {
          sb.append(i + ": EMPTY STRING!! \n");
          continue;
        }
        sb.append(i +": "+category.toString()+"\n");
      } catch (IOException e) {
        if (logger.isLoggable(Level.FINEST)) {
          logger.log(Level.FINEST, e.getMessage(), e);
        }
      }
    }
    return sb.toString();
  }
  
}
