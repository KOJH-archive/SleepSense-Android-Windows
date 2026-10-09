"""Queries Windows media sessions without sending any media command."""
import asyncio
from sleepsense.media import MediaAccess
from sleepsense.native import input_age


async def run():
    media = MediaAccess()
    await media.connect()
    try:
        rows = media.refresh()
        print(f'Windows media API connected; sessions={len(rows)}; input API valid={input_age() is not None}')
        original = [r[0] for r in rows]
        stable = True
        for _ in range(4):
            await asyncio.sleep(0.2)
            stable = stable and [r[0] for r in media.refresh()] == original
        print(f'Repeated session identities stable={stable}')
        # Source names and content metadata are deliberately not printed.
    finally:
        media.close()


asyncio.run(run())
