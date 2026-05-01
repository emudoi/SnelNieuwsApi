package com.snelnieuws.service

import com.snelnieuws.db.ArticleRepository
import com.snelnieuws.model.{Article, ArticleCreate, ArticleRow}

import scala.collection.mutable
import scala.util.Random

object ArticleService {

  private def toArticle(row: ArticleRow): Article = Article(
    id = row.id.toString,
    author = row.author,
    title = row.title,
    description = row.description,
    url = row.url,
    urlToImage = row.urlToImage,
    publishedAt = row.publishedAt,
    content = row.content,
    category = row.category
  )

  // Shuffle articles and reorder so that two articles from the same source
  // (author) are never adjacent when avoidable. Articles with no author each
  // act as their own unique source so they may sit next to each other freely.
  private def interleaveBySource(articles: List[Article]): List[Article] = {
    if (articles.length <= 1) return articles

    val buffers = mutable.Map[String, mutable.Queue[Article]]()
    var anonCounter = 0
    articles.foreach { a =>
      val key = a.author match {
        case Some(s) if s.nonEmpty => s
        case _ =>
          anonCounter += 1
          s"__anon__$anonCounter"
      }
      buffers.getOrElseUpdate(key, mutable.Queue.empty).enqueue(a)
    }
    buffers.keys.foreach { k =>
      val shuffled = Random.shuffle(buffers(k).toList)
      buffers(k) = mutable.Queue(shuffled: _*)
    }

    val result = mutable.ListBuffer[Article]()
    var lastSource: Option[String] = None
    while (buffers.nonEmpty) {
      val ranked = Random.shuffle(buffers.toList).sortBy(-_._2.size)
      val pick = ranked.find { case (k, _) => !lastSource.contains(k) }
        .orElse(ranked.headOption)
      pick.foreach { case (k, q) =>
        result += q.dequeue()
        lastSource = Some(k)
        if (q.isEmpty) buffers.remove(k)
      }
    }
    result.toList
  }

  def findAll(limit: Int = 100): List[Article] =
    interleaveBySource(ArticleRepository.findAll(limit).map(toArticle))

  def findByCategory(category: String, limit: Int = 100): List[Article] =
    interleaveBySource(ArticleRepository.findByCategory(category, limit).map(toArticle))

  def search(query: String, limit: Int = 100): List[Article] =
    interleaveBySource(ArticleRepository.search(query, limit).map(toArticle))

  def create(article: ArticleCreate): Article =
    toArticle(ArticleRepository.create(article))

  def findById(id: Long): Option[Article] =
    ArticleRepository.findById(id).map(toArticle)

  def delete(id: Long): Int =
    ArticleRepository.delete(id)

  def findCategories(): List[String] =
    ArticleRepository.findDistinctCategories()
}
