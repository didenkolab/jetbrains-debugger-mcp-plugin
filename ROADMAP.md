# Roadmap

Three capabilities this plugin does not have. Each is here because the absence was
noticed against something concrete, not because the list looked short.

## A watch list evaluated at every stop

Today every expression has to be asked for again after every step. An agent
following three values through ten lines spends thirty `evaluate_expression` calls
on one logical observation.

A per-project list of expressions, re-evaluated automatically and returned with
every pause, step and resume result, turns that into none. Round trips are the
budget this whole surface is measured against, and stepping is where they are
spent fastest.

Two constraints that decide whether it helps or hurts:

- **Cap it.** A forgotten watch list makes every step cost a fortune, silently. A
  ceiling — around 25 expressions — with a refusal that names the limit is cheaper
  than a mystery slowdown.
- **Survive a session restart.** What you are watching rarely stops being
  interesting because the process was restarted, and rebuilding the list by hand
  is exactly the round trips this was meant to save.

An expression that cannot be evaluated in the current frame must come back as an
absence with a reason, not as a missing key: "not in scope here" and "this threw"
are different facts, and a reader who cannot tell them apart draws the wrong
conclusion about their own code.

## An activity feed of what the agent did

Several tools — `get_variables`, `evaluate_expression`, `get_stack_trace` — leave
no trace on screen at all. While an agent uses them, a working session and a hung
one look identical from the IDE.

A feed in the tool window, one line per call with the tool, its arguments, the
outcome and how long it took, is what makes autonomous debugging watchable. It is
also the first thing anyone asks for when they are deciding whether to trust it.

Bound the buffer and say when lines were dropped, for the same reason
`get_session_output` does: silence must never be mistakable for quiet.

## `diff_runs`, as the headless server has it

`dbgmcp` can run the same probes over two runs and report the first point where
they stopped agreeing — the tool that turns "this value is wrong" into a file and
a line, because nothing in a single transcript says what a value should have been.

The plugin has no equivalent, so an agent that moves between an IDE session and a
headless one loses a question it could ask a moment ago. Closing that is what
keeps one companion skill honest across both.

Here it means driving a run configuration twice and comparing the transcripts,
which is more work than it sounds: the IDE owns the process lifecycle, and two
runs of the same configuration have to be kept from colliding over whatever it
binds.
