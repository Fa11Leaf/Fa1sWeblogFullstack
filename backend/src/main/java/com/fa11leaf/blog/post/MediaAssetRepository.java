package com.fa11leaf.blog.post;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    Optional<MediaAsset> findByFilename(String filename);

    Optional<MediaAsset> findBySha256(String sha256);
}
