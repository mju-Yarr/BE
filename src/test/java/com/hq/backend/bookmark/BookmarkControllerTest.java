package com.hq.backend.bookmark;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class BookmarkControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void PATCH는_지정한_필드만_바꾸고_나머지는_유지한다() throws Exception {
        String token = signupAndLogin();
        String bookmarkId = createBookmark(token, "강남역", 37.498, 127.027);

        mockMvc.perform(patch("/me/bookmarks/" + bookmarkId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeName\":\"강남역 2번출구\",\"folder\":\"자주\",\"sortOrder\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.placeName").value("강남역 2번출구"))
                .andExpect(jsonPath("$.folder").value("자주"))
                .andExpect(jsonPath("$.sortOrder").value(3))
                .andExpect(jsonPath("$.lat").value(37.498))
                .andExpect(jsonPath("$.lng").value(127.027));
    }

    @Test
    void PATCH의_좌표는_lat과_lng를_함께_지정해야_한다() throws Exception {
        String token = signupAndLogin();
        String bookmarkId = createBookmark(token, "강남역", 37.498, 127.027);

        mockMvc.perform(patch("/me/bookmarks/" + bookmarkId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lat\":37.500}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 다른_사용자의_북마크는_PATCH할_수_없다() throws Exception {
        String ownerToken = signupAndLogin();
        String bookmarkId = createBookmark(ownerToken, "강남역", 37.498, 127.027);
        String otherToken = signupAndLogin();

        mockMvc.perform(patch("/me/bookmarks/" + bookmarkId)
                        .header("Authorization", "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"placeName\":\"탈취\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOOKMARK_NOT_FOUND"));
    }

    @Test
    void bulk_delete는_소유한_북마크만_한번에_지운다() throws Exception {
        String token = signupAndLogin();
        String first = createBookmark(token, "강남역", 37.498, 127.027);
        String second = createBookmark(token, "서울시청", 37.566, 126.978);

        mockMvc.perform(post("/me/bookmarks/bulk-delete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookmarkIds\":[\"%s\",\"%s\"]}".formatted(first, second)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/me/bookmarks").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 남의_북마크가_섞인_bulk_delete는_아무것도_지우지_않는다() throws Exception {
        String ownerToken = signupAndLogin();
        String owned = createBookmark(ownerToken, "강남역", 37.498, 127.027);
        String otherToken = signupAndLogin();
        String foreign = createBookmark(otherToken, "서울시청", 37.566, 126.978);

        mockMvc.perform(post("/me/bookmarks/bulk-delete")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookmarkIds\":[\"%s\",\"%s\"]}".formatted(owned, foreign)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("BOOKMARK_NOT_FOUND"));

        mockMvc.perform(get("/me/bookmarks").header("Authorization", "Bearer " + ownerToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookmarkId").value(owned));
        mockMvc.perform(get("/me/bookmarks").header("Authorization", "Bearer " + otherToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].bookmarkId").value(foreign));
    }

    @Test
    void bulk_delete는_빈_목록을_거절한다() throws Exception {
        String token = signupAndLogin();

        mockMvc.perform(post("/me/bookmarks/bulk-delete")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookmarkIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private String createBookmark(String token, String placeName, double lat, double lng) throws Exception {
        String response = mockMvc.perform(post("/me/bookmarks")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"placeName":"%s","address":"서울","lat":%s,"lng":%s}
                                """.formatted(placeName, lat, lng)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.bookmarkId");
    }

    private String signupAndLogin() throws Exception {
        String email = "bookmark-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/auth/email/signup").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"securePassword123\"}".formatted(email)))
                .andExpect(status().isCreated());
        String response = mockMvc.perform(post("/auth/email/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"securePassword123\"}".formatted(email)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.accessToken");
    }
}
