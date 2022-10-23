package com.karolismed.mongo.polling.poll.service;

import com.karolismed.mongo.polling.auth.model.User;
import com.karolismed.mongo.polling.auth.service.UserService;
import com.karolismed.mongo.polling.config.security.UserDetailsImpl;
import com.karolismed.mongo.polling.core.exception.BadRequestException;
import com.karolismed.mongo.polling.core.exception.NotFoundException;
import com.karolismed.mongo.polling.poll.dto.CreatePollDto;
import com.karolismed.mongo.polling.poll.dto.CreatePollResponse;
import com.karolismed.mongo.polling.poll.dto.OptionDto;
import com.karolismed.mongo.polling.poll.dto.PollDto;
import com.karolismed.mongo.polling.poll.dto.PollVoteResultsDto;
import com.karolismed.mongo.polling.poll.dto.UserPollsResponse;
import com.karolismed.mongo.polling.poll.dto.VoteRequest;
import com.karolismed.mongo.polling.poll.mapper.OptionMapper;
import com.karolismed.mongo.polling.poll.mapper.PollMapper;
import com.karolismed.mongo.polling.poll.mapper.UserPollResponseMapper;
import com.karolismed.mongo.polling.poll.model.Option;
import com.karolismed.mongo.polling.poll.model.Poll;
import com.karolismed.mongo.polling.poll.model.ValueObject;
import com.karolismed.mongo.polling.poll.model.aggregation.PollSummaryAggregation;
import com.karolismed.mongo.polling.poll.model.aggregation.PollVoteResultsAggregation;
import lombok.AllArgsConstructor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ArithmeticOperators;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.aggregation.GroupOperation;
import org.springframework.data.mongodb.core.aggregation.MatchOperation;
import org.springframework.data.mongodb.core.aggregation.ProjectionOperation;
import org.springframework.data.mongodb.core.aggregation.UnwindOperation;
import org.springframework.data.mongodb.core.aggregation.VariableOperators;
import org.springframework.data.mongodb.core.mapreduce.MapReduceOptions;
import org.springframework.data.mongodb.core.mapreduce.MapReduceResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.springframework.data.mongodb.core.query.Criteria.where;

@Service
@AllArgsConstructor
public class PollService {

    private final MongoTemplate mongoTemplate;
    private final UserService userService;

    public void vote(ObjectId pollId, VoteRequest voteRequest) {
        Poll poll = getByIdOrThrow(pollId);
        if (!poll.isMultiChoice() && voteRequest.getOptionIds().size() > 1) {
            throw new BadRequestException("Poll does not support multiple votes");
        }

        Update pollUpdate = new Update();
        pollUpdate.inc("options.$[elem].voteCount");
        pollUpdate.filterArray(where("elem._id").in(voteRequest.getOptionIds()));
        mongoTemplate.updateMulti(
            new Query(where("_id").is(pollId)),
            pollUpdate,
            Poll.class
        );
    }

    public UserPollsResponse getUserPolls(ObjectId userId) {
        User user = userService.getByIdOrThrow(userId);

        Aggregation aggregation = Aggregation.newAggregation(
            Aggregation.match(new Criteria("_id").in(user.getPollIds())),
            Aggregation.unwind("options"),
            Aggregation.group("_id").first("title").as("title")
                .sum("options.voteCount").as("voteCount")
        );

        AggregationResults<PollSummaryAggregation> result =
            mongoTemplate.aggregate(aggregation, "poll", PollSummaryAggregation.class);

        return UserPollResponseMapper.map(result.getMappedResults());
    }

    public List<OptionDto> getPollOptions(ObjectId pollId) {
        List<Option> options = mongoTemplate.query(Poll.class)
            .distinct("options")
            .matching(new Query(where("_id").is(pollId)))
            .as(Option.class)
            .all();

        return options.stream().map(OptionMapper::map).collect(Collectors.toList());
    }

    public PollDto getPoll(ObjectId pollId) {
        Poll poll = getByIdOrThrow(pollId);
        return PollMapper.map(poll);
    }

    public PollVoteResultsDto getPollResults(ObjectId pollId, ObjectId userId) {
        User user = userService.getByIdOrThrow(userId);
        if (!user.getPollIds().contains(pollId)) {
            throw new BadRequestException("This poll's results can't be accessed by this user");
        }

        PollVoteResultsAggregation pollVoteResults =
            getPollVoteResultsWithMapReduce(pollId);
//            getPollVoteResultsWithAggregation(pollId);

        return Optional.ofNullable(pollVoteResults)
            .map(PollMapper::map)
            .orElse(null);
    }

    public CreatePollResponse createPoll(
        CreatePollDto createPollDto, UserDetailsImpl userDetails
    ) {
        Poll poll = PollMapper.map(createPollDto, userDetails);
        mongoTemplate.insert(poll);

        mongoTemplate.updateFirst(
            new Query(where("_id").is(userDetails.getId())),
            new Update().push("pollIds", poll.getId()),
            User.class
        );

        return CreatePollResponse.builder().id(poll.getId()).build();
    }

    private PollVoteResultsAggregation getPollVoteResultsWithAggregation(ObjectId pollId) {
        MatchOperation matchStage = Aggregation.match(new Criteria("_id").is(pollId));
        UnwindOperation unwindStage = Aggregation.unwind("options");
        GroupOperation groupStage = Aggregation.group("_id")
            .first("title").as("title")
            .addToSet("options").as("optionVotes")
            .sum("options.voteCount").as("voteCount");
        ProjectionOperation projectStage =
            Aggregation.project("title").and("voteCount").as("totalVoteCount")
                .and("_id").as("pollId")
                .and(VariableOperators.Map.itemsOf("optionVotes").as("v")
                    .andApply(
                        aggregationOperationContext -> new Document()
                            .append("optionId", "$$v._id")
                            .append("votes", "$$v.voteCount")
                            .append("value", "$$v.value")
                            .append("percentage",
                                ConditionalOperators.when(where("voteCount").is(0)).then(0).otherwise(
                                    ArithmeticOperators.Divide.valueOf(
                                        ArithmeticOperators.Multiply.valueOf("v.voteCount").multiplyBy(100)
                                    ).divideBy("voteCount")
                            ).toDocument(aggregationOperationContext))
                    )
                ).as("voteStats");

        Aggregation aggregation = Aggregation.newAggregation(
            matchStage, unwindStage, groupStage, projectStage
        );

        AggregationResults<PollVoteResultsAggregation> result =
            mongoTemplate.aggregate(aggregation, "poll", PollVoteResultsAggregation.class);
        return result.getUniqueMappedResult();
    }

    private PollVoteResultsAggregation getPollVoteResultsWithMapReduce(ObjectId pollId) {
        String mapFunc = "function() { " +
            "   for (var option of this.options) { " +
            "      emit({ " +
            "        pollId: this._id," +
            "        title: this.title" +
            "       }, option);" +
            "   }" +
            "};";
        String reduceFunc = "function(poll, options) {" +
            "    var totalVotes = Array.sum(options.map(opt => opt.voteCount)); " +
            "    for (var opt of options) { " +
            "        opt.percentage = totalVotes == 0 ? 0 : opt.voteCount / totalVotes * 100; " +
            "    } " +
            "    return { " +
            "        totalVotes, " +
            "        options " +
            "    }; " +
            "};";
        String finalizeFunc = "function(key, value) { " +
            "    key.totalVoteCount = value.totalVotes; " +
            "    key.voteStats = value.options.map(opt => ({ " +
            "        optionId: opt._id, " +
            "        votes: opt.voteCount , " +
            "        value: opt.value, " +
            "        percentage: opt.percentage " +
            "    })); " +
            "    return key; " +
            "};";

        MapReduceResults<ValueObject> results = mongoTemplate.mapReduce(
            new Query(where("_id").is(pollId)),
            "poll",
            mapFunc,
            reduceFunc,
            MapReduceOptions.options().finalizeFunction(finalizeFunc),
            ValueObject.class
        );

        return results.iterator().hasNext() ? results.iterator().next().getValue() : null;
    }

    private Poll getByIdOrThrow(ObjectId pollId) {
        return Optional.ofNullable(mongoTemplate.findById(pollId, Poll.class))
            .orElseThrow(() -> new NotFoundException(
                String.format("Poll with id %s does not exist", pollId))
            );
    }
}
