package com.game.poker.controller;

import com.game.poker.model.GameRoom;
import com.game.poker.service.GameService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {
    private final GameService gameService;

    public RoomController(GameService gameService) {
        this.gameService = gameService;
    }

    @GetMapping
    public List<Map<String, Object>> getPublicRooms() {
        List<Map<String, Object>> rooms = new ArrayList<>();
        for (GameRoom room : gameService.getAllRooms()) {
            if (!room.isPrivateRoom()) {
                Map<String, Object> item = new HashMap<>();
                item.put("roomId", room.getRoomId());
                item.put("playerCount", room.getPlayers().size());
                item.put("status", room.isStarted() ? "PLAYING" : "WAITING");
                // Expose the two room-mode flags so the lobby can render
                // 锦囊 / 技能 / 锦囊+技能 / 经典 labels on each room card.
                Map<String, Object> settings = room.getSettings();
                boolean enableScrollCards = settings != null
                        && Boolean.TRUE.equals(settings.get("enableScrollCards"));
                boolean enableSkills = settings != null
                        && Boolean.TRUE.equals(settings.get("enableSkills"));
                item.put("enableScrollCards", enableScrollCards);
                item.put("enableSkills", enableSkills);
                rooms.add(item);
            }
        }
        return rooms;
    }

    @GetMapping("/check")
    public Map<String, Object> checkRoom(@RequestParam String roomId) {
        GameRoom room = gameService.getRoomMap().get(roomId);
        Map<String, Object> response = new HashMap<>();
        response.put("exists", room != null);
        if (room != null) {
            response.put("isPrivate", room.isPrivateRoom());
        }
        return response;
    }
}
