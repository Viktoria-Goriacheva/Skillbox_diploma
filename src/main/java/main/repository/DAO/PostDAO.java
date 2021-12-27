package main.repository.DAO;

import main.service.PostOutputMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.util.List;

@Component
public class PostDAO {

    @Autowired
    private EntityManager entityManager;

    public List getPostsBySortAndSearch(int offset, int limit, PostOutputMode mode, String queryVal) {
        String searchLine = "";
        if (!queryVal.isEmpty()) {
            searchLine = " and match(p.text, title) against(\"*" + queryVal + "*\" IN BOOLEAN MODE) ";
        }
        String sortMode = "p.time";
        switch (mode) {
            case early:
                sortMode = "p.time desc";
                break;
            case best:
                sortMode = "likeCount desc";
                break;
            case popular:
                sortMode = "commentCount desc";
                break;
        }

        Query query = entityManager.createNativeQuery("SELECT " +
                "   p.id," +
                "   p.time as timestamp," +
                "   u.id as userId," +
                "   u.name," +
                "   p.title," +
                "   p.text as announce," +
                "   p.view_count as viewCount," +
                "   COUNT(pc.id) AS commentCount," +
                "   sum(case when pv.value = true then 1 else 0 end) AS likeCount," +
                "   sum(case when pv.value = false then 1 else 0 end) AS dislikeCount " +
                "FROM" +
                "   blogengine.posts p " +
                " LEFT JOIN " +
                "   post_comments pc ON post_id = p.id " +
                " LEFT JOIN " +
                "   users u ON p.user_id = u.id " +
                " LEFT JOIN " +
                "   post_votes pv ON p.id = pv.post_id " +
                "WHERE " +
                "   is_active = 1 " +
                "and " +
                "   moderation_status = 'ACCEPTED' " +
                "and " +
                "   p.time <= curdate() " +
                    searchLine +
                "GROUP BY p.id " +
                "ORDER BY " + sortMode +
                " LIMIT :limit " +
                "OFFSET :offset ", "PostsMapping").setParameter("limit", limit).setParameter("offset", offset);

        return query.getResultList();
    }

}
