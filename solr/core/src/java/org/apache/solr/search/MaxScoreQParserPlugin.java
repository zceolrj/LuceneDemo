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
package org.apache.solr.search;

import org.apache.solr.common.params.SolrParams;
import org.apache.solr.request.SolrQueryRequest;

/**
 * Parses a query like Lucene query parser, but scoring with max score, not sum
 * <br>Accepts the "tie" request parameter as with dismax. 0.0=max, 1.0=sum
 * <br>All other parameters are as with Lucene parser
 * <br>Example: <code>q=foo {!maxscore v=$myq}&myq=A OR B OR C</code>
 */
public class MaxScoreQParserPlugin extends LuceneQParserPlugin {
  public static String NAME = "maxscore";

  @Override
  public QParser createParser(String qstr, SolrParams localParams, SolrParams params, SolrQueryRequest req) {
    return new MaxScoreQParser(qstr, localParams, params, req);
  }
}

