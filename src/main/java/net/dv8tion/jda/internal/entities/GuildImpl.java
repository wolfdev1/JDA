/*
 * Copyright 2015-2019 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dv8tion.jda.internal.entities;

import gnu.trove.map.TLongObjectMap;
import net.dv8tion.jda.api.AccountType;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.Region;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.managers.AudioManager;
import net.dv8tion.jda.api.managers.GuildManager;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import net.dv8tion.jda.api.requests.restaction.MemberAction;
import net.dv8tion.jda.api.requests.restaction.RoleAction;
import net.dv8tion.jda.api.requests.restaction.order.CategoryOrderAction;
import net.dv8tion.jda.api.requests.restaction.order.ChannelOrderAction;
import net.dv8tion.jda.api.requests.restaction.order.RoleOrderAction;
import net.dv8tion.jda.api.requests.restaction.pagination.AuditLogPaginationAction;
import net.dv8tion.jda.api.utils.MiscUtil;
import net.dv8tion.jda.api.utils.cache.MemberCacheView;
import net.dv8tion.jda.api.utils.cache.SnowflakeCacheView;
import net.dv8tion.jda.api.utils.cache.SortedSnowflakeCacheView;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.managers.AudioManagerImpl;
import net.dv8tion.jda.internal.managers.GuildManagerImpl;
import net.dv8tion.jda.internal.requests.EmptyRestAction;
import net.dv8tion.jda.internal.requests.RestActionImpl;
import net.dv8tion.jda.internal.requests.Route;
import net.dv8tion.jda.internal.requests.restaction.AuditableRestActionImpl;
import net.dv8tion.jda.internal.requests.restaction.ChannelActionImpl;
import net.dv8tion.jda.internal.requests.restaction.MemberActionImpl;
import net.dv8tion.jda.internal.requests.restaction.RoleActionImpl;
import net.dv8tion.jda.internal.requests.restaction.order.CategoryOrderActionImpl;
import net.dv8tion.jda.internal.requests.restaction.order.ChannelOrderActionImpl;
import net.dv8tion.jda.internal.requests.restaction.order.RoleOrderActionImpl;
import net.dv8tion.jda.internal.requests.restaction.pagination.AuditLogPaginationActionImpl;
import net.dv8tion.jda.internal.utils.*;
import net.dv8tion.jda.internal.utils.cache.*;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class GuildImpl implements Guild
{
    private final long id;
    private final UpstreamReference<JDAImpl> api;

    private final SortedSnowflakeCacheViewImpl<Category> categoryCache = new SortedSnowflakeCacheViewImpl<>(Category.class, GuildChannel::getName, Comparator.naturalOrder());
    private final SortedSnowflakeCacheViewImpl<VoiceChannel> voiceChannelCache = new SortedSnowflakeCacheViewImpl<>(VoiceChannel.class, GuildChannel::getName, Comparator.naturalOrder());
    private final SortedSnowflakeCacheViewImpl<StoreChannel> storeChannelCache = new SortedSnowflakeCacheViewImpl<>(StoreChannel.class, StoreChannel::getName, Comparator.naturalOrder());
    private final SortedSnowflakeCacheViewImpl<TextChannel> textChannelCache = new SortedSnowflakeCacheViewImpl<>(TextChannel.class, GuildChannel::getName, Comparator.naturalOrder());
    private final SortedSnowflakeCacheViewImpl<Role> roleCache = new SortedSnowflakeCacheViewImpl<>(Role.class, Role::getName, Comparator.reverseOrder());
    private final SnowflakeCacheViewImpl<Emote> emoteCache = new SnowflakeCacheViewImpl<>(Emote.class, Emote::getName);
    private final MemberCacheViewImpl memberCache = new MemberCacheViewImpl();

    private final TLongObjectMap<DataObject> cachedPresences = MiscUtil.newLongMap();

    private final ReentrantLock mngLock = new ReentrantLock();
    private volatile GuildManager manager;

    private Member owner;
    private String name;
    private String iconId;
    private String splashId;
    private String region;
    private long ownerId;
    private Set<String> features;
    private VoiceChannel afkChannel;
    private TextChannel systemChannel;
    private Role publicRole;
    private VerificationLevel verificationLevel = VerificationLevel.UNKNOWN;
    private NotificationLevel defaultNotificationLevel = NotificationLevel.UNKNOWN;
    private MFALevel mfaLevel = MFALevel.UNKNOWN;
    private ExplicitContentLevel explicitContentLevel = ExplicitContentLevel.UNKNOWN;
    private Timeout afkTimeout;
    private boolean available;
    private boolean canSendVerification = false;

    public GuildImpl(JDAImpl api, long id)
    {
        this.id = id;
        this.api = new UpstreamReference<>(api);
    }

    @Nonnull
    @Override
    public RestAction<EnumSet<Region>> retrieveRegions(boolean includeDeprecated)
    {
        Route.CompiledRoute route = Route.Guilds.GET_VOICE_REGIONS.compile(getId());
        return new RestActionImpl<>(getJDA(), route, (response, request) ->
        {
            EnumSet<Region> set = EnumSet.noneOf(Region.class);
            DataArray arr = response.getArray();
            for (int i = 0; i < arr.length(); i++)
            {
                DataObject obj = arr.getObject(i);
                if (!includeDeprecated && obj.getBoolean("deprecated"))
                    continue;
                String id = obj.getString("id", "");
                Region region = Region.fromKey(id);
                if (region != Region.UNKNOWN)
                    set.add(region);
            }
            return set;
        });
    }

    @Nonnull
    @Override
    public MemberAction addMember(@Nonnull String accessToken, @Nonnull String userId)
    {
        Checks.notBlank(accessToken, "Access-Token");
        Checks.isSnowflake(userId, "User ID");
        Checks.check(getMemberById(userId) == null, "User is already in this guild");
        if (!getSelfMember().hasPermission(Permission.CREATE_INSTANT_INVITE))
            throw new InsufficientPermissionException(Permission.CREATE_INSTANT_INVITE);
        return new MemberActionImpl(getJDA(), this, userId, accessToken);
    }

    @Nonnull
    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public String getIconId()
    {
        return iconId;
    }

    @Override
    public String getIconUrl()
    {
        return iconId == null ? null : "https://cdn.discordapp.com/icons/" + id + "/" + iconId + ".png";
    }

    @Nonnull
    @Override
    public Set<String> getFeatures()
    {
        return features;
    }

    @Override
    public String getSplashId()
    {
        return splashId;
    }

    @Override
    public String getSplashUrl()
    {
        return splashId == null ? null : "https://cdn.discordapp.com/splashes/" + id + "/" + splashId + ".png";
    }

    @Nonnull
    @Override
    public RestAction<String> retrieveVanityUrl()
    {
        if (!getSelfMember().hasPermission(Permission.MANAGE_SERVER))
            throw new InsufficientPermissionException(Permission.MANAGE_SERVER);
        if (!getFeatures().contains("VANITY_URL"))
            throw new IllegalStateException("This guild doesn't have a vanity url");

        Route.CompiledRoute route = Route.Guilds.GET_VANITY_URL.compile(getId());

        return new RestActionImpl<>(getJDA(), route,
            (response, request) -> response.getObject().getString("code"));
    }

    @Override
    public VoiceChannel getAfkChannel()
    {
        return afkChannel;
    }

    @Override
    public TextChannel getSystemChannel()
    {
        return systemChannel;
    }

    @Nonnull
    @Override
    public RestAction<List<Webhook>> retrieveWebhooks()
    {
        if (!getSelfMember().hasPermission(Permission.MANAGE_WEBHOOKS))
            throw new InsufficientPermissionException(Permission.MANAGE_WEBHOOKS);

        Route.CompiledRoute route = Route.Guilds.GET_WEBHOOKS.compile(getId());

        return new RestActionImpl<>(getJDA(), route, (response, request) ->
        {
            DataArray array = response.getArray();
            List<Webhook> webhooks = new ArrayList<>(array.length());
            EntityBuilder builder = api.get().getEntityBuilder();

            for (int i = 0; i < array.length(); i++)
            {
                try
                {
                    webhooks.add(builder.createWebhook(array.getObject(i)));
                }
                catch (UncheckedIOException | NullPointerException e)
                {
                    JDAImpl.LOG.error("Error creating webhook from json", e);
                }
            }

            return Collections.unmodifiableList(webhooks);
        });
    }

    @Override
    public Member getOwner()
    {
        return owner;
    }

    @Override
    public long getOwnerIdLong()
    {
        return ownerId;
    }

    @Nonnull
    @Override
    public Timeout getAfkTimeout()
    {
        return afkTimeout;
    }

    @Nonnull
    @Override
    public String getRegionRaw()
    {
        return region;
    }

    @Override
    public boolean isMember(@Nonnull User user)
    {
        return memberCache.get(user.getIdLong()) != null;
    }

    @Nonnull
    @Override
    public Member getSelfMember()
    {
        Member member = getMember(getJDA().getSelfUser());
        if (member == null)
            throw new IllegalStateException("Guild does not have a self member");
        return member;
    }

    @Override
    public Member getMember(@Nonnull User user)
    {
        Checks.notNull(user, "User");
        return getMemberById(user.getIdLong());
    }

    @Nonnull
    @Override
    public MemberCacheView getMemberCache()
    {
        return memberCache;
    }

    @Nonnull
    @Override
    public SortedSnowflakeCacheView<Category> getCategoryCache()
    {
        return categoryCache;
    }

    @Nonnull
    @Override
    public SortedSnowflakeCacheView<StoreChannel> getStoreChannelCache()
    {
        return storeChannelCache;
    }

    @Nonnull
    @Override
    public SortedSnowflakeCacheView<TextChannel> getTextChannelCache()
    {
        return textChannelCache;
    }

    @Nonnull
    @Override
    public SortedSnowflakeCacheView<VoiceChannel> getVoiceChannelCache()
    {
        return voiceChannelCache;
    }

    @Nonnull
    @Override
    public SortedSnowflakeCacheView<Role> getRoleCache()
    {
        return roleCache;
    }

    @Nonnull
    @Override
    public SnowflakeCacheView<Emote> getEmoteCache()
    {
        return emoteCache;
    }

    @Nonnull
    @Override
    public List<GuildChannel> getChannels(boolean includeHidden)
    {
        Member self = getSelfMember();
        Predicate<GuildChannel> filterHidden = it -> self.hasPermission(it, Permission.VIEW_CHANNEL);

        List<GuildChannel> channels;
        SnowflakeCacheViewImpl<Category> categoryView = getCategoriesView();
        SnowflakeCacheViewImpl<VoiceChannel> voiceView = getVoiceChannelsView();
        SnowflakeCacheViewImpl<TextChannel> textView = getTextChannelsView();
        SnowflakeCacheViewImpl<StoreChannel> storeView = getStoreChannelView();
        List<TextChannel> textChannels;
        List<StoreChannel> storeChannels;
        List<VoiceChannel> voiceChannels;
        List<Category> categories;
        try (UnlockHook categoryHook = categoryView.readLock();
             UnlockHook voiceHook = voiceView.readLock();
             UnlockHook textHook = textView.readLock();
             UnlockHook storeHook = storeView.readLock())
        {
            if (includeHidden)
            {
                storeChannels = storeView.asList();
                textChannels = textView.asList();
                voiceChannels = voiceView.asList();
            }
            else
            {
                storeChannels = storeView.stream().filter(filterHidden).collect(Collectors.toList());
                textChannels = textView.stream().filter(filterHidden).collect(Collectors.toList());
                voiceChannels = voiceView.stream().filter(filterHidden).collect(Collectors.toList());
            }
            categories = categoryView.asList(); // we filter categories out when they are empty (no visible channels inside)
            channels = new ArrayList<>((int) categoryView.size() + voiceChannels.size() + textChannels.size() + storeChannels.size());
        }

        storeChannels.stream().filter(it -> it.getParent() == null).forEach(channels::add);
        textChannels.stream().filter(it -> it.getParent() == null).forEach(channels::add);
        Collections.sort(channels);
        voiceChannels.stream().filter(it -> it.getParent() == null).forEach(channels::add);

        for (Category category : categories)
        {
            List<GuildChannel> children;
            if (includeHidden)
            {
                children = category.getChannels();
            }
            else
            {
                children = category.getChannels().stream().filter(filterHidden).collect(Collectors.toList());
                if (children.isEmpty())
                    continue;
            }

            channels.add(category);
            channels.addAll(children);
        }

        return Collections.unmodifiableList(channels);
    }

    @Nonnull
    @Override
    public RestAction<List<ListedEmote>> retrieveEmotes()
    {
        Route.CompiledRoute route = Route.Emotes.GET_EMOTES.compile(getId());
        return new RestActionImpl<>(getJDA(), route, (response, request) ->
        {

            EntityBuilder builder = GuildImpl.this.getJDA().getEntityBuilder();
            DataArray emotes = response.getArray();
            List<ListedEmote> list = new ArrayList<>(emotes.length());
            for (int i = 0; i < emotes.length(); i++)
            {
                DataObject emote = emotes.getObject(i);
                list.add(builder.createEmote(GuildImpl.this, emote, true));
            }

            return Collections.unmodifiableList(list);
        });
    }

    @Nonnull
    @Override
    public RestAction<ListedEmote> retrieveEmoteById(@Nonnull String id)
    {
        Checks.isSnowflake(id, "Emote ID");
        Emote emote = getEmoteById(id);
        if (emote != null)
        {
            ListedEmote listedEmote = (ListedEmote) emote;
            if (listedEmote.hasUser() || !getSelfMember().hasPermission(Permission.MANAGE_EMOTES))
                return new EmptyRestAction<>(getJDA(), listedEmote);
        }
        Route.CompiledRoute route = Route.Emotes.GET_EMOTE.compile(getId(), id);
        return new RestActionImpl<>(getJDA(), route, (response, request) ->
        {
            EntityBuilder builder = GuildImpl.this.getJDA().getEntityBuilder();
            return builder.createEmote(GuildImpl.this, response.getObject(), true);
        });
    }

    @Nonnull
    @Override
    public RestActionImpl<List<Ban>> retrieveBanList()
    {
        if (!getSelfMember().hasPermission(Permission.BAN_MEMBERS))
            throw new InsufficientPermissionException(Permission.BAN_MEMBERS);

        Route.CompiledRoute route = Route.Guilds.GET_BANS.compile(getId());
        return new RestActionImpl<>(getJDA(), route, (response, request) ->
        {
            EntityBuilder builder = api.get().getEntityBuilder();
            List<Ban> bans = new LinkedList<>();
            DataArray bannedArr = response.getArray();

            for (int i = 0; i < bannedArr.length(); i++)
            {
                final DataObject object = bannedArr.getObject(i);
                DataObject user = object.getObject("user");
                bans.add(new Ban(builder.createFakeUser(user, false), object.getString("reason", null)));
            }
            return Collections.unmodifiableList(bans);
        });
    }

    @Nonnull
    @Override
    public RestAction<Ban> retrieveBanById(@Nonnull String userId)
    {
        if (!getSelfMember().hasPermission(Permission.BAN_MEMBERS))
            throw new InsufficientPermissionException(Permission.BAN_MEMBERS);

        Checks.isSnowflake(userId, "User ID");

        Route.CompiledRoute route = Route.Guilds.GET_BAN.compile(getId(), userId);
        return new RestActionImpl<>(getJDA(), route, (response, request) ->
        {

            EntityBuilder builder = api.get().getEntityBuilder();
            DataObject bannedObj = response.getObject();
            DataObject user = bannedObj.getObject("user");
            return new Ban(builder.createFakeUser(user, false), bannedObj.getString("reason", null));
        });
    }

    @Nonnull
    @Override
    public RestAction<Integer> retrievePrunableMemberCount(int days)
    {
        if (!getSelfMember().hasPermission(Permission.KICK_MEMBERS))
            throw new InsufficientPermissionException(Permission.KICK_MEMBERS);

        if (days < 1)
            throw new IllegalArgumentException("Days amount must be at minimum 1 day.");

        Route.CompiledRoute route = Route.Guilds.PRUNABLE_COUNT.compile(getId()).withQueryParams("days", Integer.toString(days));
        return new RestActionImpl<>(getJDA(), route, (response, request) -> response.getObject().getInt("pruned"));
    }

    @Nonnull
    @Override
    public Role getPublicRole()
    {
        return publicRole;
    }

    @Nullable
    @Override
    public TextChannel getDefaultChannel()
    {
        final Role role = getPublicRole();
        return getTextChannelsView().stream()
                                    .filter(c -> role.hasPermission(c, Permission.MESSAGE_READ))
                                    .min(Comparator.naturalOrder()).orElse(null);
    }

    @Nonnull
    @Override
    public GuildManager getManager()
    {
        GuildManager mng = manager;
        if (mng == null)
        {
            mng = MiscUtil.locked(mngLock, () ->
            {
                if (manager == null)
                    manager = new GuildManagerImpl(this);
                return manager;
            });
        }
        return mng;
    }

    @Nonnull
    @Override
    public AuditLogPaginationAction retrieveAuditLogs()
    {
        return new AuditLogPaginationActionImpl(this);
    }

    @Nonnull
    @Override
    public RestAction<Void> leave()
    {
        if (owner.equals(getSelfMember()))
            throw new IllegalStateException("Cannot leave a guild that you are the owner of! Transfer guild ownership first!");

        Route.CompiledRoute route = Route.Self.LEAVE_GUILD.compile(getId());
        return new RestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public RestAction<Void> delete()
    {
        if (!getJDA().getSelfUser().isBot() && getJDA().getSelfUser().isMfaEnabled())
            throw new IllegalStateException("Cannot delete a guild without providing MFA code. Use Guild#delete(String)");

        return delete(null);
    }

    @Nonnull
    @Override
    public RestAction<Void> delete(String mfaCode)
    {
        if (!owner.equals(getSelfMember()))
            throw new PermissionException("Cannot delete a guild that you do not own!");

        DataObject mfaBody = null;
        if (!getJDA().getSelfUser().isBot() && getJDA().getSelfUser().isMfaEnabled())
        {
            Checks.notEmpty(mfaCode, "Provided MultiFactor Auth code");
            mfaBody = DataObject.empty().put("code", mfaCode);
        }

        Route.CompiledRoute route = Route.Guilds.DELETE_GUILD.compile(getId());
        return new RestActionImpl<>(getJDA(), route, mfaBody);
    }

    @Nonnull
    @Override
    public AudioManager getAudioManager()
    {
        if (!getJDA().isAudioEnabled())
            throw new IllegalStateException("Audio is disabled. Cannot retrieve an AudioManager while audio is disabled.");

        final AbstractCacheView<AudioManager> managerMap = getJDA().getAudioManagersView();
        AudioManager mng = managerMap.get(id);
        if (mng == null)
        {
            // No previous manager found -> create one
            try (UnlockHook hook = managerMap.writeLock())
            {
                GuildImpl cachedGuild = (GuildImpl) getJDA().getGuildById(id);
                if (cachedGuild == null)
                    throw new IllegalStateException("Cannot get an AudioManager instance on an uncached Guild");
                mng = managerMap.get(id);
                if (mng == null)
                {
                    mng = new AudioManagerImpl(cachedGuild);
                    managerMap.getMap().put(id, mng);
                }
            }
        }
        return mng;
    }

    @Nonnull
    @Override
    public JDAImpl getJDA()
    {
        return api.get();
    }

    @Nonnull
    @Override
    public List<GuildVoiceState> getVoiceStates()
    {
        return Collections.unmodifiableList(
                getMembersView().stream().map(Member::getVoiceState).filter(Objects::nonNull).collect(Collectors.toList()));
    }

    @Nonnull
    @Override
    public VerificationLevel getVerificationLevel()
    {
        return verificationLevel;
    }

    @Nonnull
    @Override
    public NotificationLevel getDefaultNotificationLevel()
    {
        return defaultNotificationLevel;
    }

    @Nonnull
    @Override
    public MFALevel getRequiredMFALevel()
    {
        return mfaLevel;
    }

    @Nonnull
    @Override
    public ExplicitContentLevel getExplicitContentLevel()
    {
        return explicitContentLevel;
    }

    @Override
    public boolean checkVerification()
    {
        if (getJDA().getAccountType() == AccountType.BOT)
            return true;
        if(canSendVerification)
            return true;

        if (getJDA().getSelfUser().getPhoneNumber() != null)
            return canSendVerification = true;

        switch (verificationLevel)
        {
            case VERY_HIGH:
                break; // we already checked for a verified phone number
            case HIGH:
                if (ChronoUnit.MINUTES.between(getSelfMember().getTimeJoined(), OffsetDateTime.now()) < 10)
                    break;
            case MEDIUM:
                if (ChronoUnit.MINUTES.between(getJDA().getSelfUser().getTimeCreated(), OffsetDateTime.now()) < 5)
                    break;
            case LOW:
                if (!getJDA().getSelfUser().isVerified())
                    break;
            case NONE:
                canSendVerification = true;
                return true;
            case UNKNOWN:
                return true; // try and let discord decide
        }
        return false;
    }

    @Override
    public boolean isAvailable()
    {
        return available;
    }

    @Override
    public long getIdLong()
    {
        return id;
    }

    @Nonnull
    @Override
    public RestAction<List<Invite>> retrieveInvites()
    {
        if (!this.getSelfMember().hasPermission(Permission.MANAGE_SERVER))
            throw new InsufficientPermissionException(Permission.MANAGE_SERVER);

        final Route.CompiledRoute route = Route.Invites.GET_GUILD_INVITES.compile(getId());

        return new RestActionImpl<>(getJDA(), route, (response, request) ->
        {
            EntityBuilder entityBuilder = api.get().getEntityBuilder();
            DataArray array = response.getArray();
            List<Invite> invites = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++)
                invites.add(entityBuilder.createInvite(array.getObject(i)));
            return Collections.unmodifiableList(invites);
        });
    }

    @Nonnull
    @Override
    public RestAction<Void> moveVoiceMember(@Nonnull Member member, @Nullable VoiceChannel voiceChannel)
    {
        Checks.notNull(member, "Member");
        checkGuild(member.getGuild(), "Member");
        if (voiceChannel != null)
            checkGuild(voiceChannel.getGuild(), "VoiceChannel");

        GuildVoiceState vState = member.getVoiceState();
        if (vState == null)
            throw new IllegalStateException("Cannot move a Member with disabled CacheFlag.VOICE_STATE");
        if (!vState.inVoiceChannel())
            throw new IllegalStateException("You cannot move a Member who isn't in a VoiceChannel!");

        if (!PermissionUtil.checkPermission(vState.getChannel(), getSelfMember(), Permission.VOICE_MOVE_OTHERS))
            throw new InsufficientPermissionException(Permission.VOICE_MOVE_OTHERS, "This account does not have Permission to MOVE_OTHERS out of the channel that the Member is currently in.");

        if (voiceChannel != null
            && !PermissionUtil.checkPermission(voiceChannel, getSelfMember(), Permission.VOICE_CONNECT)
            && !PermissionUtil.checkPermission(voiceChannel, member, Permission.VOICE_CONNECT))
            throw new InsufficientPermissionException(Permission.VOICE_CONNECT,
                                                      "Neither this account nor the Member that is attempting to be moved have the VOICE_CONNECT permission " +
                                                      "for the destination VoiceChannel, so the move cannot be done.");

        DataObject body = DataObject.empty().put("channel_id", voiceChannel == null ? null : voiceChannel.getId());
        Route.CompiledRoute route = Route.Guilds.MODIFY_MEMBER.compile(getId(), member.getUser().getId());
        return new RestActionImpl<>(getJDA(), route, body);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> modifyNickname(@Nonnull Member member, String nickname)
    {
        Checks.notNull(member, "Member");
        checkGuild(member.getGuild(), "Member");

        if(member.equals(getSelfMember()))
        {
            if(!member.hasPermission(Permission.NICKNAME_CHANGE)
               && !member.hasPermission(Permission.NICKNAME_MANAGE))
                throw new InsufficientPermissionException(Permission.NICKNAME_CHANGE, "You neither have NICKNAME_CHANGE nor NICKNAME_MANAGE permission!");
        }
        else
        {
            checkPermission(Permission.NICKNAME_MANAGE);
            checkPosition(member);
        }

        if (Objects.equals(nickname, member.getNickname()))
            return new EmptyRestAction<>(getJDA(), null);

        if (nickname == null)
            nickname = "";

        DataObject body = DataObject.empty().put("nick", nickname);

        Route.CompiledRoute route;
        if (member.equals(getSelfMember()))
            route = Route.Guilds.MODIFY_SELF_NICK.compile(getId());
        else
            route = Route.Guilds.MODIFY_MEMBER.compile(getId(), member.getUser().getId());

        return new AuditableRestActionImpl<>(getJDA(), route, body);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Integer> prune(int days)
    {
        checkPermission(Permission.KICK_MEMBERS);

        Checks.check(days >= 1, "Days amount must be at minimum 1 day.");

        Route.CompiledRoute route = Route.Guilds.PRUNE_MEMBERS.compile(getId()).withQueryParams("days", Integer.toString(days));
        return new AuditableRestActionImpl<>(getJDA(), route, (response, request) -> response.getObject().getInt("pruned"));
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> kick(@Nonnull Member member, String reason)
    {
        Checks.notNull(member, "member");
        checkGuild(member.getGuild(), "member");
        checkPermission(Permission.KICK_MEMBERS);
        checkPosition(member);

        final String userId = member.getUser().getId();
        final String guildId = getId();

        Route.CompiledRoute route = Route.Guilds.KICK_MEMBER.compile(guildId, userId);
        if (reason != null && !reason.isEmpty())
            route = route.withQueryParams("reason", EncodingUtil.encodeUTF8(reason));

        return new AuditableRestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> ban(@Nonnull User user, int delDays, String reason)
    {
        Checks.notNull(user, "User");
        checkPermission(Permission.BAN_MEMBERS);

        if (isMember(user)) // If user is in guild. Check if we are able to ban.
            checkPosition(getMember(user));

        Checks.notNegative(delDays, "Deletion Days");

        Checks.check(delDays <= 7, "Deletion Days must not be bigger than 7.");

        final String userId = user.getId();

        Route.CompiledRoute route = Route.Guilds.BAN.compile(getId(), userId);
        if (reason != null && !reason.isEmpty())
            route = route.withQueryParams("reason", EncodingUtil.encodeUTF8(reason));
        if (delDays > 0)
            route = route.withQueryParams("delete-message-days", Integer.toString(delDays));

        return new AuditableRestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> ban(@Nonnull String userId, int delDays, String reason)
    {
        Checks.notNull(userId, "User");
        checkPermission(Permission.BAN_MEMBERS);

        User user = getJDA().getUserById(userId);
        if (user != null) // If we have the user cached then we should use the additional information available to use during the ban process.
            return ban(user, delDays, reason);

        Checks.notNegative(delDays, "Deletion Days");

        Checks.check(delDays <= 7, "Deletion Days must not be bigger than 7.");

        Route.CompiledRoute route = Route.Guilds.BAN.compile(getId(), userId);
        if (reason != null && !reason.isEmpty())
            route = route.withQueryParams("reason", EncodingUtil.encodeUTF8(reason));
        if (delDays > 0)
            route = route.withQueryParams("delete-message-days", Integer.toString(delDays));

        return new AuditableRestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> unban(@Nonnull String userId)
    {
        Checks.isSnowflake(userId, "User ID");
        checkPermission(Permission.BAN_MEMBERS);

        Route.CompiledRoute route = Route.Guilds.UNBAN.compile(getId(), userId);
        return new AuditableRestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> deafen(@Nonnull Member member, boolean deafen)
    {
        Checks.notNull(member, "Member");
        checkGuild(member.getGuild(), "Member");
        checkPermission(Permission.VOICE_DEAF_OTHERS);

        //We check the owner instead of Position because, apparently, Discord doesn't care about position for
        // muting and deafening, only whether the affected Member is the owner.
        if (member.equals(getOwner()))
            throw new HierarchyException("Cannot modify Guild Deafen status the Owner of the Guild");

        GuildVoiceState voiceState = member.getVoiceState();
        if (voiceState != null)
        {
            if (voiceState.getChannel() == null)
                throw new IllegalStateException("Can only deafen members who are currently in a voice channel");
            if (voiceState.isGuildDeafened() == deafen)
                return new EmptyRestAction<>(getJDA(), null);
        }

        DataObject body = DataObject.empty().put("deaf", deafen);
        Route.CompiledRoute route = Route.Guilds.MODIFY_MEMBER.compile(getId(), member.getUser().getId());
        return new AuditableRestActionImpl<>(getJDA(), route, body);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> mute(@Nonnull Member member, boolean mute)
    {
        Checks.notNull(member, "Member");
        checkGuild(member.getGuild(), "Member");
        checkPermission(Permission.VOICE_MUTE_OTHERS);

        //We check the owner instead of Position because, apparently, Discord doesn't care about position for
        // muting and deafening, only whether the affected Member is the owner.
        if (member.equals(getOwner()))
            throw new HierarchyException("Cannot modify Guild Mute status the Owner of the Guild");

        GuildVoiceState voiceState = member.getVoiceState();
        if (voiceState != null)
        {
            if (voiceState.getChannel() == null)
                throw new IllegalStateException("Can only mute members who are currently in a voice channel");
            if (voiceState.isGuildMuted() == mute)
                return new EmptyRestAction<>(getJDA(), null);
        }

        DataObject body = DataObject.empty().put("mute", mute);
        Route.CompiledRoute route = Route.Guilds.MODIFY_MEMBER.compile(getId(), member.getUser().getId());
        return new AuditableRestActionImpl<>(getJDA(), route, body);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> addRoleToMember(@Nonnull Member member, @Nonnull Role role)
    {
        Checks.notNull(member, "Member");
        Checks.notNull(role, "Role");
        checkGuild(member.getGuild(), "Member");
        checkGuild(role.getGuild(), "Role");
        checkPermission(Permission.MANAGE_ROLES);
        checkPosition(role);

        Route.CompiledRoute route = Route.Guilds.ADD_MEMBER_ROLE.compile(getId(), member.getUser().getId(), role.getId());
        return new AuditableRestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> removeRoleFromMember(@Nonnull Member member, @Nonnull Role role)
    {
        Checks.notNull(member, "Member");
        Checks.notNull(role, "Role");
        checkGuild(member.getGuild(), "Member");
        checkGuild(role.getGuild(), "Role");
        checkPermission(Permission.MANAGE_ROLES);
        checkPosition(role);

        Route.CompiledRoute route = Route.Guilds.REMOVE_MEMBER_ROLE.compile(getId(), member.getUser().getId(), role.getId());
        return new AuditableRestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> modifyMemberRoles(@Nonnull Member member, Collection<Role> rolesToAdd, Collection<Role> rolesToRemove)
    {
        Checks.notNull(member, "Member");
        checkGuild(member.getGuild(), "Member");
        checkPermission(Permission.MANAGE_ROLES);
        Set<Role> currentRoles = new HashSet<>(((MemberImpl) member).getRoleSet());
        if (rolesToAdd != null)
        {
            checkRoles(rolesToAdd, "add", "to");
            currentRoles.addAll(rolesToAdd);
        }

        if (rolesToRemove != null)
        {
            checkRoles(rolesToRemove, "remove", "from");
            currentRoles.removeAll(rolesToRemove);
        }

        return modifyMemberRoles(member, currentRoles);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> modifyMemberRoles(@Nonnull Member member, @Nonnull Collection<Role> roles)
    {
        Checks.notNull(member, "Member");
        Checks.notNull(roles, "Roles");
        checkGuild(member.getGuild(), "Member");
        roles.forEach(role ->
        {
            Checks.notNull(role, "Role in collection");
            checkGuild(role.getGuild(), "Role: " + role.toString());
            checkPosition(role);
        });

        Checks.check(!roles.contains(getPublicRole()),
             "Cannot add the PublicRole of a Guild to a Member. All members have this role by default!");

        // Return an empty rest action if there were no changes
        final List<Role> memberRoles = member.getRoles();
        if (Helpers.deepEqualsUnordered(roles, memberRoles))
            return new EmptyRestAction<>(getJDA());

        //Make sure that the current managed roles are preserved and no new ones are added.
        List<Role> currentManaged = memberRoles.stream().filter(Role::isManaged).collect(Collectors.toList());
        List<Role> newManaged = roles.stream().filter(Role::isManaged).collect(Collectors.toList());
        if (!Helpers.deepEqualsUnordered(newManaged, currentManaged))
        {
            List<Role> added = new ArrayList<>(newManaged);
            added.removeAll(currentManaged);
            List<Role> removed = new ArrayList<>(currentManaged);
            removed.removeAll(added);
            throw new IllegalArgumentException("Cannot modify managed roles from a member! Added: " + added + " Removed: " + removed);
        }

        DataObject body = DataObject.empty()
            .put("roles", roles.stream().map(Role::getId).collect(Collectors.toList()));
        Route.CompiledRoute route = Route.Guilds.MODIFY_MEMBER.compile(getId(), member.getUser().getId());

        return new AuditableRestActionImpl<>(getJDA(), route, body);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> transferOwnership(@Nonnull Member newOwner)
    {
        Checks.notNull(newOwner, "Member");
        checkGuild(newOwner.getGuild(), "Member");
        if (!getSelfMember().equals(getOwner()))
            throw new PermissionException("The logged in account must be the owner of this Guild to be able to transfer ownership");

        Checks.check(!getSelfMember().equals(newOwner),
                     "The member provided as the newOwner is the currently logged in account. Provide a different member to give ownership to.");

        Checks.check(!newOwner.getUser().isBot(), "Cannot transfer ownership of a Guild to a Bot!");

        DataObject body = DataObject.empty().put("owner_id", newOwner.getUser().getId());
        Route.CompiledRoute route = Route.Guilds.MODIFY_GUILD.compile(getId());
        return new AuditableRestActionImpl<>(getJDA(), route, body);
    }

    @Nonnull
    @Override
    public ChannelAction<TextChannel> createTextChannel(@Nonnull String name)
    {
        checkPermission(Permission.MANAGE_CHANNEL);
        Checks.notBlank(name, "Name");
        name = name.trim();

        Checks.check(name.length() > 0 && name.length() <= 100, "Provided name must be 1 - 100 characters in length");
        return new ChannelActionImpl<>(TextChannel.class, name, this, ChannelType.TEXT);
    }

    @Nonnull
    @Override
    public ChannelAction<VoiceChannel> createVoiceChannel(@Nonnull String name)
    {
        checkPermission(Permission.MANAGE_CHANNEL);
        Checks.notBlank(name, "Name");
        name = name.trim();

        Checks.check(name.length() > 0 && name.length() <= 100, "Provided name must be 1 - 100 characters in length");
        return new ChannelActionImpl<>(VoiceChannel.class, name, this, ChannelType.VOICE);
    }

    @Nonnull
    @Override
    public ChannelAction<Category> createCategory(@Nonnull String name)
    {
        checkPermission(Permission.MANAGE_CHANNEL);
        Checks.notBlank(name, "Name");
        name = name.trim();

        Checks.check(name.length() > 0 && name.length() <= 100, "Provided name must be 1 - 100 characters in length");
        return new ChannelActionImpl<>(Category.class, name, this, ChannelType.CATEGORY);
    }

    @Nonnull
    @Override
    public RoleAction createRole()
    {
        checkPermission(Permission.MANAGE_ROLES);
        return new RoleActionImpl(this);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Emote> createEmote(@Nonnull String name, @Nonnull Icon icon, @Nonnull Role... roles)
    {
        checkPermission(Permission.MANAGE_EMOTES);
        Checks.notBlank(name, "Emote name");
        Checks.notNull(icon, "Emote icon");
        Checks.notNull(roles, "Roles");

        DataObject body = DataObject.empty();
        body.put("name", name);
        body.put("image", icon.getEncoding());
        if (roles.length > 0) // making sure none of the provided roles are null before mapping them to the snowflake id
            body.put("roles", Stream.of(roles).filter(Objects::nonNull).map(ISnowflake::getId).collect(Collectors.toSet()));

        JDAImpl jda = getJDA();
        Route.CompiledRoute route = Route.Emotes.CREATE_EMOTE.compile(getId());
        return new AuditableRestActionImpl<>(jda, route, body, (response, request) ->
        {
            DataObject obj = response.getObject();
            return jda.getEntityBuilder().createEmote(this, obj, true);
        });
    }

    @Nonnull
    @Override
    public ChannelOrderAction modifyCategoryPositions()
    {
        return new ChannelOrderActionImpl(this, ChannelType.CATEGORY.getSortBucket());
    }

    @Nonnull
    @Override
    public ChannelOrderAction modifyTextChannelPositions()
    {
        return new ChannelOrderActionImpl(this, ChannelType.TEXT.getSortBucket());
    }

    @Nonnull
    @Override
    public ChannelOrderAction modifyVoiceChannelPositions()
    {
        return new ChannelOrderActionImpl(this, ChannelType.VOICE.getSortBucket());
    }

    @Nonnull
    @Override
    public CategoryOrderAction modifyTextChannelPositions(@Nonnull Category category)
    {
        Checks.notNull(category, "Category");
        checkGuild(category.getGuild(), "Category");
        return new CategoryOrderActionImpl(category, ChannelType.TEXT.getSortBucket());
    }

    @Nonnull
    @Override
    public CategoryOrderAction modifyVoiceChannelPositions(@Nonnull Category category)
    {
        Checks.notNull(category, "Category");
        checkGuild(category.getGuild(), "Category");
        return new CategoryOrderActionImpl(category, ChannelType.VOICE.getSortBucket());
    }

    @Nonnull
    @Override
    public RoleOrderAction modifyRolePositions(boolean useAscendingOrder)
    {
        return new RoleOrderActionImpl(this, useAscendingOrder);
    }

    protected void checkGuild(Guild providedGuild, String comment)
    {
        if (!equals(providedGuild))
            throw new IllegalArgumentException("Provided " + comment + " is not part of this Guild!");
    }

    protected void checkPermission(Permission perm)
    {
        if (!getSelfMember().hasPermission(perm))
            throw new InsufficientPermissionException(perm);
    }

    protected void checkPosition(Member member)
    {
        if(!getSelfMember().canInteract(member))
            throw new HierarchyException("Can't modify a member with higher or equal highest role than yourself!");
    }

    protected void checkPosition(Role role)
    {
        if(!getSelfMember().canInteract(role))
            throw new HierarchyException("Can't modify a role with higher or equal highest role than yourself! Role: " + role.toString());
    }

    private void checkRoles(Collection<Role> roles, String type, String preposition)
    {
        roles.forEach(role ->
        {
            Checks.notNull(role, "Role in roles to " + type);
            checkGuild(role.getGuild(), "Role: " + role.toString());
            checkPosition(role);
            Checks.check(!role.isManaged(), "Cannot %s a managed role %s a Member. Role: %s", type, preposition, role.toString());
        });
    }

    // ---- Setters -----

    public GuildImpl setAvailable(boolean available)
    {
        this.available = available;
        return this;
    }

    public GuildImpl setOwner(Member owner)
    {
        this.owner = owner;
        return this;
    }

    public GuildImpl setName(String name)
    {
        this.name = name;
        return this;
    }

    public GuildImpl setIconId(String iconId)
    {
        this.iconId = iconId;
        return this;
    }

    public GuildImpl setFeatures(Set<String> features)
    {
        this.features = Collections.unmodifiableSet(features);
        return this;
    }

    public GuildImpl setSplashId(String splashId)
    {
        this.splashId = splashId;
        return this;
    }

    public GuildImpl setRegion(String region)
    {
        this.region = region;
        return this;
    }

    public GuildImpl setAfkChannel(VoiceChannel afkChannel)
    {
        this.afkChannel = afkChannel;
        return this;
    }

    public GuildImpl setSystemChannel(TextChannel systemChannel)
    {
        this.systemChannel = systemChannel;
        return this;
    }

    public GuildImpl setPublicRole(Role publicRole)
    {
        this.publicRole = publicRole;
        return this;
    }

    public GuildImpl setVerificationLevel(VerificationLevel level)
    {
        this.verificationLevel = level;
        this.canSendVerification = false;   //recalc on next send
        return this;
    }

    public GuildImpl setDefaultNotificationLevel(NotificationLevel level)
    {
        this.defaultNotificationLevel = level;
        return this;
    }

    public GuildImpl setRequiredMFALevel(MFALevel level)
    {
        this.mfaLevel = level;
        return this;
    }

    public GuildImpl setExplicitContentLevel(ExplicitContentLevel level)
    {
        this.explicitContentLevel = level;
        return this;
    }

    public GuildImpl setAfkTimeout(Timeout afkTimeout)
    {
        this.afkTimeout = afkTimeout;
        return this;
    }

    public GuildImpl setOwnerId(long ownerId)
    {
        this.ownerId = ownerId;
        return this;
    }

    // -- Map getters --

    public SortedSnowflakeCacheViewImpl<Category> getCategoriesView()
    {
        return categoryCache;
    }

    public SortedSnowflakeCacheViewImpl<StoreChannel> getStoreChannelView()
    {
        return storeChannelCache;
    }

    public SortedSnowflakeCacheViewImpl<TextChannel> getTextChannelsView()
    {
        return textChannelCache;
    }

    public SortedSnowflakeCacheViewImpl<VoiceChannel> getVoiceChannelsView()
    {
        return voiceChannelCache;
    }

    public SortedSnowflakeCacheViewImpl<Role> getRolesView()
    {
        return roleCache;
    }

    public SnowflakeCacheViewImpl<Emote> getEmotesView()
    {
        return emoteCache;
    }

    public MemberCacheViewImpl getMembersView()
    {
        return memberCache;
    }

    public TLongObjectMap<DataObject> getCachedPresenceMap()
    {
        return cachedPresences;
    }


    // -- Object overrides --

    @Override
    public boolean equals(Object o)
    {
        if (o == this)
            return true;
        if (!(o instanceof GuildImpl))
            return false;
        GuildImpl oGuild = (GuildImpl) o;
        return this.id == oGuild.id;
    }

    @Override
    public int hashCode()
    {
        return Long.hashCode(id);
    }

    @Override
    public String toString()
    {
        return "G:" + getName() + '(' + id + ')';
    }
}
