package main.api.response;

import main.dto.TagDTO;

import java.util.List;

public class TagResponse {

    private List<TagDTO> tags;

    public TagResponse(List<TagDTO> tags) {
        this.tags = tags;
    }

    public List<TagDTO> getTags() {
        return tags;
    }

    public void setTags(List<TagDTO> tags) {
        this.tags = tags;
    }
}
