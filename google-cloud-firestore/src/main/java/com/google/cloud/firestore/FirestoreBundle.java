/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.firestore;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Query.LimitType;
import com.google.firestore.proto.BundleElement;
import com.google.firestore.proto.BundleMetadata;
import com.google.firestore.proto.BundledDocumentMetadata;
import com.google.firestore.proto.BundledQuery;
import com.google.firestore.proto.NamedQuery;
import com.google.firestore.v1.Document;
import com.google.firestore.v1.RunQueryRequest;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class FirestoreBundle {

  static int BUNDLE_SCHEMA_VERSION = 1;

  private byte[] bundleData;

  public static final class Builder {
    private String id;
    // Resulting documents for the bundle, keyed by full document path.
    private Map<String, BundledDocument> documents  = new HashMap<>();
    // Named queries saved in the bundle, keyed by query name.
    private Map<String, NamedQuery> namedQueries = new HashMap<>();
    // The latest read time among all bundled documents and queries.
    private Timestamp latestReadTime = Timestamp.MIN_VALUE;
    // Printer to encode protobuf objects into JSON string.
    private JsonFormat.Printer printer = JsonFormat.printer();

    public Builder(String id) {
      this.id = id;
    }

    public Builder add(DocumentSnapshot documentSnap) {
      BundledDocumentMetadata metadata = BundledDocumentMetadata.newBuilder()
          .setName(documentSnap.getReference().getName())
          .setReadTime(documentSnap.getReadTime().toProto())
          .setExists(documentSnap.exists())
          .build();
      Document document = documentSnap.toPb().build().getUpdate();
      documents.put(metadata.getName(), new BundledDocument(metadata, document));

      if(documentSnap.getReadTime().compareTo(latestReadTime) > 0) {
        latestReadTime = documentSnap.getReadTime();
      }

      return this;
    }

    public Builder add(String queryName, QuerySnapshot querySnap) {
      RunQueryRequest queryProto = querySnap.getQuery().toProto();
      LimitType limitType = querySnap.getQuery().options.getLimitType();
      NamedQuery namedQuery = NamedQuery.newBuilder()
          .setName(queryName)
          .setReadTime(querySnap.getReadTime().toProto())
          .setBundledQuery(BundledQuery.newBuilder()
              .setParent(queryProto.getParent())
              .setStructuredQuery(queryProto.getStructuredQuery())
              .setLimitType(limitType.equals(LimitType.Last) ? BundledQuery.LimitType.LAST : BundledQuery.LimitType.FIRST)
          ).build();
      namedQueries.put(queryName, namedQuery);

      for(QueryDocumentSnapshot snapshot : querySnap.getDocuments()) {
        add(snapshot);
      }

      if(querySnap.getReadTime().compareTo(latestReadTime) > 0) {
        latestReadTime = querySnap.getReadTime();
      }

      return this;
    }

    public FirestoreBundle build() {
      StringBuilder buffer = new StringBuilder();

      for(NamedQuery namedQuery : namedQueries.values()) {
        buffer.append(elementToLengthPrefixedStringBuilder(BundleElement.newBuilder().setNamedQuery(namedQuery).build()));
      }

      for(BundledDocument bundledDocument : documents.values()) {
        buffer.append(elementToLengthPrefixedStringBuilder(BundleElement.newBuilder().setDocumentMetadata(bundledDocument.getMetadata()).build()));
        if(bundledDocument.getDocument() != null) {
          buffer.append(BundleElement.newBuilder().setDocument(bundledDocument.getDocument()).build());
        }
      }

      BundleMetadata metadata = BundleMetadata.newBuilder()
          .setId(id)
          .setCreateTime(latestReadTime.toProto())
          .setVersion(BUNDLE_SCHEMA_VERSION)
          .setTotalDocuments(documents.size())
          .setTotalBytes(buffer.toString().getBytes().length)
          .build();
      BundleElement element = BundleElement.newBuilder().setMetadata(metadata).build();
      buffer.append(elementToLengthPrefixedStringBuilder(element));

      return new FirestoreBundle(buffer.toString().getBytes(StandardCharsets.UTF_8));
    }

    private StringBuilder elementToLengthPrefixedStringBuilder(BundleElement element) {
      String elementJson = null;
      try {
        elementJson = printer.print(element);
      } catch (InvalidProtocolBufferException e) {
        throw new RuntimeException(e);
      }
      return new StringBuilder().append(elementJson.getBytes().length).append(elementJson);
    }
  }

  private FirestoreBundle(byte[] data) {
    bundleData = data;
  }

  public ByteBuffer toByteBuffer() {
    return ByteBuffer.wrap(bundleData).asReadOnlyBuffer();
  }
}

class BundledDocument {
  private BundledDocumentMetadata metadata;
  private Document document;

  BundledDocument(BundledDocumentMetadata metadata, Document document){
    this.metadata = metadata;
    this.document = document;
  }

  public BundledDocumentMetadata getMetadata() {
    return metadata;
  }

  public Document getDocument() {
    return document;
  }
}
