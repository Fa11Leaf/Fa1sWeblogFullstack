package com.fa11leaf.blog.publish;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PublishJobRepository extends JpaRepository<PublishJob, Long> {

    /** 某篇文章的发布历史，最近的在前。 */
    List<PublishJob> findByPostIdOrderByIdDesc(Long postId);
}
