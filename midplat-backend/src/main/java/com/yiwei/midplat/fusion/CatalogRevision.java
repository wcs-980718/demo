package com.yiwei.midplat.fusion;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="midplat_catalog_revision", uniqueConstraints=@UniqueConstraint(columnNames={"resource_type","resource_id","content_hash"}))
public class CatalogRevision {
    @Id @Column(length=64) private String id;
    @Column(name="resource_type",nullable=false,length=16) private String resourceType;
    @Column(name="resource_id",nullable=false,length=64) private String resourceId;
    @Column(name="content_hash",nullable=false,length=64) private String contentHash;
    @Column(nullable=false,columnDefinition="text") private String content;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    protected CatalogRevision() {}
    CatalogRevision(String id,String type,String resourceId,String hash,String content) {
        this.id=id;this.resourceType=type;this.resourceId=resourceId;this.contentHash=hash;this.content=content;this.createdAt=Instant.now();
    }
    public String getId(){return id;}
    public String getResourceType(){return resourceType;}
    public String getResourceId(){return resourceId;}
    public String getContentHash(){return contentHash;}
    public String getContent(){return content;}
    public Instant getCreatedAt(){return createdAt;}
}
